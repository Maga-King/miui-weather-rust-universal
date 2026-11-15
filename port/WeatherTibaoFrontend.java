package dev.weather.oneplus;

import android.content.Context;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

/** Chinese Genie frontend: original g2pM, Jieba boundaries, tone sandhi and RoBERTa. */
public final class WeatherTibaoFrontend implements AutoCloseable {
    private static final String ASSETS = "tibao_frontend/";
    private static final int BERT_DIM = 1024;
    private static final String PUNCT = "!?…,. -".replace(" ", "");
    private static final String DIGITS = "零一二三四五六七八九";
    private final OrtEnvironment environment;
    private final OrtSession bertSession, g2pmSession;
    private final Lexicon lexicon;
    private final Map<String, Long> vocabulary = new HashMap<>();
    private final Set<String> neutralWords = new HashSet<>(), fullToneWords = new HashSet<>();

    public static final class Result {
        public final long[] phones;
        /** Row-major [phones.length,1024], matching t2s_encoder target BERT input. */
        public final float[] bert;
        public final int[] word2ph;
        public final String normalizedText;
        public final long[] tokenIds;
        Result(long[] p, float[] b, int[] w, String t, long[] ids) {
            phones=p; bert=b; word2ph=w; normalizedText=t; tokenIds=ids;
        }
    }

    public WeatherTibaoFrontend(Context context, OrtEnvironment environment, OrtSession bert) throws IOException, OrtException {
        this(environment, bert, context.getAssets().open(ASSETS+"lexicon.bin.gz"),
                context.getAssets().open(ASSETS+"vocab.txt"), context.getAssets().open(ASSETS+"tone_words.txt"),
                readAll(context.getAssets().open(ASSETS+"g2pm.onnx")));
    }

    // Package access makes the complete Android implementation testable on the JVM.
    WeatherTibaoFrontend(OrtEnvironment environment, OrtSession bert, InputStream dictionary,
            InputStream vocab, InputStream tones, byte[] g2pm) throws IOException, OrtException {
        this.environment=environment;
        bertSession=Objects.requireNonNull(bert, "RoBERTa session is required");
        lexicon=new Lexicon(dictionary);
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(vocab,"UTF-8"))) {
            String line; long index=0;
            while ((line=reader.readLine())!=null) vocabulary.put(line,index++);
        }
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(tones,"UTF-8"))) {
            neutralWords.addAll(Arrays.asList(reader.readLine().split(" ")));
            fullToneWords.addAll(Arrays.asList(reader.readLine().split(" ")));
        }
        try (OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            g2pmSession=environment.createSession(g2pm,options);
        }
    }

    public synchronized Result prepare(String input) throws IOException, OrtException {
        String text=normalize(input);
        if (text.isEmpty()) throw new IllegalArgumentException("No Chinese speech text");
        // The approved V2ProPlus synthesis path prepends a sentence boundary.
        if (!text.startsWith(".")) text="."+text;
        if (text.length()>510) throw new IllegalArgumentException("Chinese speech chunk exceeds RoBERTa's 510 character limit");
        List<Long> phones=new ArrayList<>();
        int[] repeats=new int[text.length()];
        int begin=0;
        for (int i=0;i<text.length();i++) if (isPunct(text.charAt(i))) {
            convertSegment(text.substring(begin,i+1),begin,phones,repeats); begin=i+1;
        }
        if (begin<text.length()) convertSegment(text.substring(begin),begin,phones,repeats);
        long[] sequence=new long[phones.size()];
        for (int i=0;i<sequence.length;i++) sequence[i]=phones.get(i);
        long[] ids=new long[text.length()+2]; ids[0]=101; ids[ids.length-1]=102;
        for (int i=0;i<text.length();i++) {
            // BertNormalizer separates every BMP Han character and punctuation.
            String token=String.valueOf(text.charAt(i));
            Long value=vocabulary.get(token);
            if (value==null && isPunct(text.charAt(i))) value=vocabulary.get("[UNK]");
            if (value==null) throw new IllegalArgumentException("RoBERTa has no token for "+token);
            ids[i+1]=value;
        }
        float[] features=bert(ids,repeats,sequence.length);
        return new Result(sequence,features,repeats,text,ids);
    }

    private void convertSegment(String text,int offset,List<Long> output,int[] repeats) throws OrtException {
        String[] pinyins=new String[text.length()];
        boolean neural=false;
        long[] ids=new long[text.length()+2];
        ids[0]=lexicon.charIds.get("시"); ids[ids.length-1]=lexicon.charIds.get("끝");
        for (int i=0;i<text.length();i++) {
            String c=text.substring(i,i+1);
            ids[i+1]=lexicon.charIds.containsKey(c)?lexicon.charIds.get(c):lexicon.charIds.get("<UNK>");
            String[] pronunciations=lexicon.pronunciations.get(c);
            if (pronunciations==null) {
                if (!isPunct(text.charAt(i))) throw new IllegalArgumentException("Unsupported Chinese pronunciation: "+c);
                pinyins[i]=c;
            } else if (pronunciations.length==1) pinyins[i]=pronunciations[0];
            else neural=true;
        }
        if (neural) try (OnnxTensor tensor=OnnxTensor.createTensor(environment,LongBuffer.wrap(ids),new long[]{1,ids.length});
                OrtSession.Result result=g2pmSession.run(Collections.singletonMap("input_ids",tensor))) {
            LongBuffer predicted=((OnnxTensor)result.get(0)).getLongBuffer();
            for (int i=0;i<pinyins.length;i++) if (pinyins[i]==null) pinyins[i]=lexicon.classes[(int)predicted.get(i+1)];
        }
        List<Word> words=preMerge(lexicon.segment(text,true));
        int cursor=0;
        for (Word word:words) {
            String[] py=Arrays.copyOfRange(pinyins,cursor,cursor+word.text.length());
            String[] correction=lexicon.corrections.get(word.text);
            if (correction!=null) {
                if (correction.length!=py.length) throw new IllegalArgumentException("Pronunciation length mismatch: "+word.text);
                py=correction.clone();
            } else for (int i=0;i<py.length;i++) {
                String[] single=lexicon.corrections.get(word.text.substring(i,i+1));
                if (single!=null) py[i]=single[0];
            }
            modifiedTone(word,py);
            for (int i=0;i<py.length;i++) {
                char c=word.text.charAt(i);
                if (isPunct(c)) { output.add(lexicon.symbolIds.get(String.valueOf(c))); repeats[offset+cursor+i]=1; continue; }
                String[] pair=lexicon.pinyinPhones.get(py[i].replace("u:","v"));
                if (pair==null) throw new IllegalArgumentException("Unsupported pinyin "+py[i]+" for "+c);
                for (String phone:pair) {
                    Long phoneId=lexicon.symbolIds.get(phone);
                    if (phoneId==null) throw new IllegalArgumentException("Unsupported phoneme "+phone);
                    output.add(phoneId);
                }
                repeats[offset+cursor+i]=pair.length;
            }
            cursor+=word.text.length();
        }
        if (cursor!=text.length()) throw new IllegalStateException("Chinese word alignment failed");
    }

    private float[] bert(long[] ids,int[] repeats,int phoneCount) throws OrtException, IOException {
        Map<String,OnnxTensor> inputs=new HashMap<>();
        long[] attention=new long[ids.length]; Arrays.fill(attention,1);
        long[] count=new long[repeats.length]; for(int i=0;i<count.length;i++) count[i]=repeats[i];
        try {
            for (String name:bertSession.getInputNames()) {
                if (name.equals("input_ids")) inputs.put(name,OnnxTensor.createTensor(environment,LongBuffer.wrap(ids),new long[]{1,ids.length}));
                else if(name.equals("attention_mask")) inputs.put(name,OnnxTensor.createTensor(environment,LongBuffer.wrap(attention),new long[]{1,ids.length}));
                else if(name.equals("token_type_ids")) inputs.put(name,OnnxTensor.createTensor(environment,LongBuffer.wrap(new long[ids.length]),new long[]{1,ids.length}));
                else if(name.equals("repeats")) inputs.put(name,OnnxTensor.createTensor(environment,LongBuffer.wrap(count),new long[]{count.length}));
                else throw new IOException("Unsupported RoBERTa input: "+name);
            }
            try(OrtSession.Result result=bertSession.run(inputs)) {
                OnnxTensor output=(OnnxTensor)result.get(0);
                long[] shape=output.getInfo().getShape();
                int rows;
                if(shape.length==2 && shape[1]==BERT_DIM) rows=(int)shape[0];
                else if(shape.length==3 && shape[0]==1 && shape[2]==BERT_DIM) rows=(int)shape[1];
                else throw new IOException("Unsupported RoBERTa output shape: "+Arrays.toString(shape));
                float[] values=new float[rows*BERT_DIM];
                FloatBuffer buffer=output.getFloatBuffer();
                if(buffer==null) throw new IOException("RoBERTa did not return floating point features");
                buffer.get(values);
                float[] expanded;
                if(rows==phoneCount) expanded=values;
                else if(rows==repeats.length || rows==repeats.length+2) {
                    expanded=new float[phoneCount*BERT_DIM]; int at=0,skip=rows==repeats.length+2?1:0;
                    for(int i=0;i<repeats.length;i++) for(int j=0;j<repeats[i];j++) {
                        System.arraycopy(values,(i+skip)*BERT_DIM,expanded,at*BERT_DIM,BERT_DIM); at++;
                    }
                    if(at!=phoneCount) throw new IOException("RoBERTa phone alignment mismatch");
                } else throw new IOException("RoBERTa returned "+rows+" rows for "+phoneCount+" phones");
                boolean nonzero=false;
                for(float value:expanded) {
                    if(Float.isNaN(value)||Float.isInfinite(value)) throw new IOException("Invalid RoBERTa features");
                    nonzero|=value!=0;
                }
                if(!nonzero) throw new IOException("RoBERTa returned empty features");
                return expanded;
            }
        } finally { for(OnnxTensor input:inputs.values()) input.close(); }
    }

    private List<Word> preMerge(List<Word> source) {
        List<Word> words=new ArrayList<>(); String last="";
        for(Word item:source) {
            String text=last.equals("不")?last+item.text:item.text;
            if(!text.equals("不")) words.add(new Word(text,item.pos)); last=text;
        }
        if(last.equals("不")) words.add(new Word(last,"d"));
        List<Word> next=new ArrayList<>();
        for(int i=0;i<words.size();i++) {
            Word w=words.get(i);
            if(w.text.equals("一") && !next.isEmpty() && i+1<words.size()) {
                Word previous=next.get(next.size()-1), following=words.get(i+1);
                if(previous.text.equals(following.text)&&previous.pos.equals("v")&&following.pos.equals("v")) {
                    previous.text+=w.text+following.text; i++; continue;
                }
            }
            next.add(new Word(w.text,w.pos));
        }
        words=new ArrayList<>();
        for(Word w:next) {
            if(!words.isEmpty()&&words.get(words.size()-1).text.equals("一")) words.get(words.size()-1).text+=w.text;
            else words.add(w);
        }
        next=new ArrayList<>();
        for(Word w:words) {
            if(!next.isEmpty()&&next.get(next.size()-1).text.equals(w.text)) next.get(next.size()-1).text+=w.text;
            else next.add(w);
        }
        words=mergeThree(next,true); words=mergeThree(words,false);
        next=new ArrayList<>();
        for(Word w:words) {
            if(!next.isEmpty()&&w.text.equals("儿")&&!next.get(next.size()-1).text.equals("#")) next.get(next.size()-1).text+=w.text;
            else next.add(w);
        }
        return next;
    }

    private List<Word> mergeThree(List<Word> words,boolean all) {
        List<Word> merged=new ArrayList<>(); boolean[] flags=new boolean[words.size()];
        for(int i=0;i<words.size();i++) {
            Word w=words.get(i);
            if(i>0) {
                String previous=words.get(i-1).text;
                boolean tones=all?lexicon.allThree(previous)&&lexicon.allThree(w.text):lexicon.defaultTone(previous.charAt(previous.length()-1))==3&&lexicon.defaultTone(w.text.charAt(0))==3;
                if(tones&&!flags[i-1]&&!reduplicated(previous)&&previous.length()+w.text.length()<=3) {
                    merged.get(merged.size()-1).text+=w.text; flags[i]=true; continue;
                }
            }
            merged.add(new Word(w.text,w.pos));
        }
        return merged;
    }

    private void modifiedTone(Word word,String[] p) {
        String text=word.text; int n=text.length();
        if(n==3&&text.charAt(1)=='不') tone(p,1,5);
        else for(int i=0;i<n-1;i++) if(text.charAt(i)=='不'&&tone(p[i+1])==4) tone(p,i,2);
        boolean numeric=true;
        for(int i=0;i<n;i++) if(!Character.isDigit(text.charAt(i))&&"零〇一二三四五六七八九十百千万亿两".indexOf(text.charAt(i))<0) numeric=false;
        if(text.indexOf('一')>=0&&!numeric) {
            if(n==3&&text.charAt(1)=='一'&&text.charAt(0)==text.charAt(2)) tone(p,1,5);
            else if(text.startsWith("第一")) tone(p,1,1);
            else for(int i=0;i<n-1;i++) if(text.charAt(i)=='一'&&!isPunct(text.charAt(i+1))) tone(p,i,tone(p[i+1])==4?2:4);
        }
        for(int i=1;i<n;i++) if(text.charAt(i)==text.charAt(i-1)&&"nva".contains(word.pos.substring(0,1))&&!fullToneWords.contains(text)) tone(p,i,5);
        char end=text.charAt(n-1); int ge=text.indexOf('个');
        if("吧呢哈啊呐噻嘛吖嗨哦哒额滴哩哟喽啰耶喔诶的地得".indexOf(end)>=0) tone(p,n-1,5);
        else if(n==1&&"了着过".indexOf(end)>=0&&Arrays.asList("ul","uz","ug").contains(word.pos)) tone(p,n-1,5);
        else if(n>1&&"们子".indexOf(end)>=0&&Arrays.asList("r","n").contains(word.pos)&&!fullToneWords.contains(text)) tone(p,n-1,5);
        else if(n>1&&"上下里".indexOf(end)>=0&&Arrays.asList("s","l","f").contains(word.pos)) tone(p,n-1,5);
        else if(n>1&&"来去".indexOf(end)>=0&&"上下进出回过起开".indexOf(text.charAt(n-2))>=0) tone(p,n-1,5);
        else if(text.equals("个") || ge>=1&&(Character.isDigit(text.charAt(ge-1))||"零〇一二三四五六七八九十百千万亿几有两半多各整每做是".indexOf(text.charAt(ge-1))>=0)) tone(p,ge,5);
        else if(neutral(text)) tone(p,n-1,5);
        int split=lexicon.splitWord(text);
        if(split>0&&neutral(text.substring(0,split))) tone(p,split-1,5);
        if(split<n&&neutral(text.substring(split))) tone(p,n-1,5);
        if(n==2&&allThree(p,0,n)) tone(p,0,2);
        else if(n==3) {
            if(allThree(p,0,n)) {
                if(split==2) {tone(p,0,2);tone(p,1,2);} else if(split==1) tone(p,1,2);
            } else {
                if(split==2&&allThree(p,0,2)) tone(p,0,2);
                if(split==1&&allThree(p,1,3)) tone(p,1,2);
                else if(split>0&&split<n&&!allThree(p,split,n)&&tone(p[split])==3&&tone(p[split-1])==3) tone(p,split-1,2);
            }
        } else if(n==4) {if(allThree(p,0,2)) tone(p,0,2);if(allThree(p,2,4)) tone(p,2,2);}
        // Upstream keeps a phone pair per character even for rhotic endings.
        if(end=='儿'&&p[n-1].equals("er1")) p[n-1]="er2";
        if(end=='儿'&&n>1&&(p[n-1].equals("er2")||p[n-1].equals("er5"))&&!Arrays.asList("a","j","nr").contains(word.pos)
                &&!NO_ERHUA.contains(text)&&!NO_ERHUA.contains(text.substring(n-2))) p[n-1]="er"+tone(p[n-2]);
    }
    private static final Set<String> NO_ERHUA=new HashSet<>(Arrays.asList(("虐儿 为儿 护儿 瞒儿 救儿 替儿 有儿 一儿 我儿 俺儿 妻儿 拐儿 聋儿 乞儿 患儿 幼儿 孤儿 婴儿 婴幼儿 连体儿 脑瘫儿 流浪儿 体弱儿 混血儿 蜜雪儿 舫儿 祖儿 美儿 应采儿 可儿 侄儿 孙儿 侄孙儿 女儿 男儿 红孩儿 花儿 虫儿 马儿 鸟儿 猪儿 猫儿 狗儿 少儿").split(" ")));
    private boolean neutral(String word) {return neutralWords.contains(word)||word.length()>=2&&neutralWords.contains(word.substring(word.length()-2));}
    private static boolean allThree(String[] p,int from,int to) {for(int i=from;i<to;i++) if(tone(p[i])!=3)return false;return true;}
    private static int tone(String p) {char c=p.charAt(p.length()-1);return c>='1'&&c<='5'?c-'0':0;}
    private static void tone(String[] p,int index,int t) {if(index>=0&&tone(p[index])!=0)p[index]=p[index].substring(0,p[index].length()-1)+t;}
    private static boolean reduplicated(String s) {return s.length()==2&&s.charAt(0)==s.charAt(1);}
    private static boolean isPunct(char c) {return PUNCT.indexOf(c)>=0;}

    private interface Replace {String apply(Matcher m);}
    private static String replace(String input,String expression,Replace replacer) {
        Matcher matcher=Pattern.compile(expression).matcher(input); StringBuffer out=new StringBuffer();
        while(matcher.find()) matcher.appendReplacement(out,Matcher.quoteReplacement(replacer.apply(matcher)));
        return matcher.appendTail(out).toString();
    }

    String normalize(String input) {
        if(input==null) throw new IllegalArgumentException("Speech text is null");
        StringBuilder chars=new StringBuilder();
        for(int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            if(c>=0xff01&&c<=0xff5e)c-=0xfee0;
            String[] simple=lexicon.conversion.get(String.valueOf(c));
            chars.append(simple==null?String.valueOf(c):simple[0]);
        }
        String s=chars.toString().replaceAll("\\s+","");
        s=s.replace("km/h","千米每小时").replace("m/s","米每秒").replace("μg/m³","微克每立方米").replace("μg/m3","微克每立方米");
        s=s.replace("PM2.5","细颗粒物").replace("pm2.5","细颗粒物").replace("PM10","可吸入颗粒物").replace("pm10","可吸入颗粒物").replace("AQI","空气质量指数").replace("UV","紫外线");
        s=s.replace("mm","毫米").replace("cm²","平方厘米").replace("cm³","立方厘米").replace("cm","厘米").replace("km","千米").replace("kg","千克").replace("ml","毫升").replace("m²","平方米").replace("m³","立方米");
        s=replace(s,"(\\d{4})([-/.])(0[1-9]|1[012])\\2(0[1-9]|[12][0-9]|3[01])",m->digit(m.group(1),false)+"年"+number(m.group(3))+"月"+number(m.group(4))+"日");
        s=replace(s,"(\\d{4}|\\d{2})年",m->digit(m.group(1),false)+"年");
        s=replace(s,"([0-1]?[0-9]|2[0-3]):([0-5][0-9])(?::([0-5][0-9]))?([~-])([0-1]?[0-9]|2[0-3]):([0-5][0-9])(?::([0-5][0-9]))?",m->time(m.group(1),m.group(2),m.group(3))+"至"+time(m.group(5),m.group(6),m.group(7)));
        s=replace(s,"([0-1]?[0-9]|2[0-3]):([0-5][0-9])(?::([0-5][0-9]))?",m->time(m.group(1),m.group(2),m.group(3)));
        s=replace(s,"(-?\\d+(?:\\.\\d+)?)(°C|℃|度|摄氏度|%|毫米|厘米|千米|米)[~～至](-?\\d+(?:\\.\\d+)?)(°C|℃|度|摄氏度|%|毫米|厘米|千米|米)",m->m.group(1)+m.group(2)+"至"+m.group(3)+m.group(4));
        // Temperature ranges are spoken as weather, including both negative signs.
        s=replace(s,"(-?\\d+(?:\\.\\d+)?)[-~～至](-?\\d+(?:\\.\\d+)?)(°C|℃|度|摄氏度)",m->temperature(m.group(1))+"到"+temperature(m.group(2))+m.group(3).replace("°C","度").replace("℃","度"));
        s=replace(s,"(-?)(\\d+(?:\\.\\d+)?)(°C|℃|度|摄氏度)",m->(m.group(1).isEmpty()?"":"零下")+number(m.group(2))+(m.group(3).equals("摄氏度")?"摄氏度":"度"));
        s=replace(s,"(-?)(\\d+(?:\\.\\d+)?)%",m->(m.group(1).isEmpty()?"":"负")+"百分之"+number(m.group(2)));
        s=replace(s,"(-?)(\\d+)/(\\d+)",m->(m.group(1).isEmpty()?"":"负")+number(m.group(3))+"分之"+number(m.group(2)));
        s=replace(s,"(-?\\d+(?:\\.\\d+)?)[-~～](-?\\d+(?:\\.\\d+)?)(级|毫米|厘米|千米|米|千克|小时)",m->signed(m.group(1))+"到"+signed(m.group(2))+m.group(3));
        s=replace(s,"(-?\\d+(?:\\.\\d+)?)[~～](-?\\d+(?:\\.\\d+)?)",m->signed(m.group(1))+"到"+signed(m.group(2)));
        s=replace(s,"(\\d+(?:\\.\\d+)?)([+×÷=])(\\d+(?:\\.\\d+)?)",m->m.group(1)+(m.group(2).equals("+")?"加":m.group(2).equals("×")?"乘":m.group(2).equals("÷")?"除":"等于")+m.group(3));
        s=replace(s,"(-)(\\d+(?:\\.\\d+)?)",m->"负"+number(m.group(2)));
        s=replace(s,"\\d+\\.\\d+",m->number(m.group()));
        s=replace(s,"(空气质量指数|气压|能见度|紫外线指数|浓度|指数)(为|是)?(\\d+)",m->m.group(1)+(m.group(2)==null?"":m.group(2))+number(m.group(3)));
        s=replace(s,"(\\d+)(百帕|千帕|帕斯卡|微克|毫克)",m->number(m.group(1))+m.group(2));
        s=replace(s,"(\\d+)([多余几+])?(处|台|架|间|人|个|天|小时|年|月|日|秒|分|千克|千米|厘米|毫米|米|层|公顷|公里)",m->{String v=number(m.group(1));return (v.equals("二")?"两":v)+(m.group(2)==null?"":m.group(2).replace("+","多"))+m.group(3);});
        s=replace(s,"\\d{3,}",m->digit(m.group(),true));
        s=replace(s,"\\d+",m->number(m.group()));
        s=s.replace("...","…").replace('：',',').replace(':',',').replace('；',',').replace(';',',').replace('，',',').replace('。','.').replace('！','!').replace('？','?').replace('·',',').replace('、',',').replace('$','.').replace('/', '每').replace('—','-').replace('~','…').replace('～','…');
        StringBuilder clean=new StringBuilder();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c>='\u4e00'&&c<='\u9fa5'||isPunct(c)) {
                if(isPunct(c)&&clean.length()>0&&clean.charAt(clean.length()-1)==c)continue;
                clean.append(c);
            } else if(Character.isLetter(c)||Character.isDigit(c)||Character.isSurrogate(c)) throw new IllegalArgumentException("Unsupported speech character: "+c);
        }
        return clean.toString();
    }
    private static String temperature(String s) {return s.startsWith("-")?"零下"+number(s.substring(1)):number(s);}
    private static String signed(String s) {return s.startsWith("-")?"负"+number(s.substring(1)):number(s);}
    private static String time(String h,String m,String sec) {
        String result=number(h)+"点";
        if(Integer.parseInt(m)!=0)result+=m.equals("30")?"半":(m.startsWith("0")?"零":"")+number(m)+"分";
        if(sec!=null&&Integer.parseInt(sec)!=0)result+=(sec.startsWith("0")?"零":"")+number(sec)+"秒";
        return result;
    }
    private static String digit(String s,boolean alt) {StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++)b.append(alt&&s.charAt(i)=='1'?'幺':DIGITS.charAt(s.charAt(i)-'0'));return b.toString();}
    private static String number(String s) {
        String[] parts=s.split("\\.",-1);String integer=parts[0].replaceFirst("^0+","");
        String result=integer.isEmpty()?"零":cardinal(integer,false);
        if(result.startsWith("一十"))result=result.substring(1);
        if(parts.length>1&&!parts[1].isEmpty()) {
            String decimal=parts[1];if(decimal.endsWith("0"))decimal=decimal.replaceFirst("0+$","")+"0";
            result+="点"+digit(decimal,false);
        }
        return result;
    }
    private static String cardinal(String s,boolean zero) {
        String stripped=s.replaceFirst("^0+","");
        if(stripped.isEmpty())return "";
        if(stripped.length()==1)return (zero&&stripped.length()<s.length()?"零":"")+DIGITS.charAt(stripped.charAt(0)-'0');
        int unit=stripped.length()>8?8:stripped.length()>4?4:stripped.length()>3?3:stripped.length()>2?2:1;
        String label=unit==8?"亿":unit==4?"万":unit==3?"千":unit==2?"百":"十";
        return cardinal(s.substring(0,s.length()-unit),true)+label+cardinal(s.substring(s.length()-unit),true);
    }

    @Override public synchronized void close() throws OrtException {g2pmSession.close();}
    private static byte[] readAll(InputStream stream) throws IOException {try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private static final class Word {String text;final String pos;Word(String text,String pos){this.text=text;this.pos=pos.isEmpty()?"x":pos;}}
    private static final class Entry {final int frequency;final String pos;Entry(int f,String p){frequency=f;pos=p;}}

    private static final class Lexicon {
        final Map<String,Long> symbolIds=new HashMap<>(),charIds=new HashMap<>();
        final Map<String,String[]> pronunciations,corrections,pinyinPhones,defaults,conversion;
        final String[] classes;
        final Map<String,Entry> words=new HashMap<>(); final Set<String> prefixes=new HashSet<>();
        final Hmm posHmm,wordHmm; final double logTotal;
        Lexicon(InputStream stream)throws IOException {
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(stream)))) {
                if(in.readInt()!=0x54424631)throw new IOException("Unsupported Tibao lexicon");
                String[] symbols=strings(in);for(int i=0;i<symbols.length;i++)symbolIds.put(symbols[i],(long)i);
                int size=in.readInt();for(int i=0;i<size;i++)charIds.put(string(in),(long)in.readInt());
                classes=strings(in);pronunciations=map(in);corrections=map(in);pinyinPhones=map(in);defaults=map(in);conversion=map(in);
                size=in.readInt();long total=0;
                for(int i=0;i<size;i++) {
                    String w=string(in);int f=in.readInt();String p=string(in);words.put(w,new Entry(f,p));total+=f;
                    for(int n=1;n<=w.length();n++)prefixes.add(w.substring(0,n));
                }
                logTotal=Math.log(total);posHmm=new Hmm(in,true);wordHmm=new Hmm(in,false);
            }
        }
        int defaultTone(char c){String[] p=defaults.get(String.valueOf(c));return p==null?0:tone(p[0]);}
        boolean allThree(String s){for(int i=0;i<s.length();i++)if(defaultTone(s.charAt(i))!=3)return false;return true;}
        List<Word> segment(String text,boolean pos) {
            List<Word> result=new ArrayList<>();int begin=0;
            for(int i=0;i<text.length();i++)if(isPunct(text.charAt(i))) {
                if(begin<i)dag(text.substring(begin,i),pos,result);result.add(new Word(text.substring(i,i+1),"x"));begin=i+1;
            }
            if(begin<text.length())dag(text.substring(begin),pos,result);return result;
        }
        private void dag(String s,boolean pos,List<Word> result) {
            int n=s.length();double[] scores=new double[n+1];int[] next=new int[n];
            for(int i=n-1;i>=0;i--) {
                double best=Double.NEGATIVE_INFINITY;int end=i+1;
                for(int j=i+1;j<=n;j++) {
                    String part=s.substring(i,j);if(!prefixes.contains(part))break;Entry entry=words.get(part);
                    if(entry!=null&&entry.frequency>0) {double value=Math.log(entry.frequency)-logTotal+scores[j];if(value>=best){best=value;end=j;}}
                }
                if(best==Double.NEGATIVE_INFINITY)best=-logTotal+scores[i+1];scores[i]=best;next[i]=end;
            }
            StringBuilder buffer=new StringBuilder();
            for(int i=0;i<n;) {
                int end=next[i];String part=s.substring(i,end);
                if(end-i==1)buffer.append(part);
                else {flush(buffer,pos,result);Entry entry=words.get(part);result.add(new Word(part,entry==null?"x":entry.pos));}
                i=end;
            }
            flush(buffer,pos,result);
        }
        private void flush(StringBuilder buffer,boolean pos,List<Word> result) {
            if(buffer.length()==0)return;String s=buffer.toString();buffer.setLength(0);Entry e=words.get(s);
            if(s.length()==1||e!=null&&e.frequency>0) {
                for(int i=0;i<s.length();i++){String c=s.substring(i,i+1);Entry v=words.get(c);result.add(new Word(c,v==null?"x":v.pos));}
            } else result.addAll((pos?posHmm:wordHmm).cut(s));
        }
        int splitWord(String text) {
            List<Word> segmented=segment(text,false);List<String> search=new ArrayList<>();
            for(Word word:segmented) {
                String s=word.text;
                if(s.length()>2)for(int i=0;i<s.length()-1;i++){String sub=s.substring(i,i+2);if(words.containsKey(sub)&&words.get(sub).frequency>0)search.add(sub);}
                if(s.length()>3)for(int i=0;i<s.length()-2;i++){String sub=s.substring(i,i+3);if(words.containsKey(sub)&&words.get(sub).frequency>0)search.add(sub);}
                search.add(s);
            }
            String shortest=search.get(0);for(String s:search)if(s.length()<shortest.length())shortest=s;
            return text.indexOf(shortest)==0?shortest.length():text.length()-shortest.length();
        }
        static String string(DataInputStream in)throws IOException {int n=in.readInt();if(n<0||n>10000000)throw new IOException("Invalid frontend string length");byte[] b=new byte[n];in.readFully(b);return new String(b,"UTF-8");}
        static String[] strings(DataInputStream in)throws IOException {String[] s=new String[in.readInt()];for(int i=0;i<s.length;i++)s[i]=string(in);return s;}
        static Map<String,String[]> map(DataInputStream in)throws IOException {int n=in.readInt();Map<String,String[]> m=new HashMap<>();for(int i=0;i<n;i++)m.put(string(in),strings(in));return m;}
    }

    /** Exported original Jieba POS and word HMMs, including exact probability ties. */
    private static final class Hmm {
        final String[] boundary,pos;final double[] start;final double[][] transition;
        final Map<Integer,Double>[] emission;final Map<Integer,int[]> possible=new HashMap<>();final boolean tags;
        @SuppressWarnings("unchecked") Hmm(DataInputStream in,boolean tags)throws IOException {
            this.tags=tags;int n=in.readInt();boundary=new String[n];pos=new String[n];start=new double[n];transition=new double[n][n];emission=(Map<Integer,Double>[])new Map[n];
            for(int i=0;i<n;i++) {
                boundary[i]=Lexicon.string(in);pos[i]=Lexicon.string(in);start[i]=in.readDouble();Arrays.fill(transition[i],Double.NEGATIVE_INFINITY);
                int count=in.readInt();for(int j=0;j<count;j++)transition[i][in.readInt()]=in.readDouble();
                emission[i]=new HashMap<>();count=in.readInt();for(int j=0;j<count;j++)emission[i].put(in.readInt(),in.readDouble());
            }
            int count=in.readInt();for(int i=0;i<count;i++){int c=in.readInt();int[] states=new int[in.readInt()];for(int j=0;j<states.length;j++)states[j]=in.readInt();possible.put(c,states);}
        }
        List<Word> cut(String s) {
            int n=s.length(),k=start.length;double[] previous=new double[k];Arrays.fill(previous,Double.NEGATIVE_INFINITY);int[][] back=new int[n][k];
            int[] first=possible.get((int)s.charAt(0));boolean[] active=new boolean[k];
            if(first==null)Arrays.fill(active,true);else for(int v:first)active[v]=true;
            for(int y=0;y<k;y++)if(active[y])previous[y]=start[y]+emission[y].getOrDefault((int)s.charAt(0),-3.14e100);
            for(int t=1;t<n;t++) {
                boolean[] expected=new boolean[k];for(int x=0;x<k;x++)if(active[x])for(int y=0;y<k;y++)if(transition[x][y]!=Double.NEGATIVE_INFINITY)expected[y]=true;
                boolean[] allowed=new boolean[k];int[] states=possible.get((int)s.charAt(t));
                if(states==null)allowed=expected.clone();else for(int y:states)allowed[y]=expected[y];
                boolean any=false;for(boolean v:allowed)any|=v;if(!any){allowed=expected;for(boolean v:allowed)any|=v;if(!any)Arrays.fill(allowed,true);}
                double[] current=new double[k];Arrays.fill(current,Double.NEGATIVE_INFINITY);
                for(int y=0;y<k;y++)if(allowed[y]) {
                    double e=emission[y].getOrDefault((int)s.charAt(t),-3.14e100);int best=0;double probability=Double.NEGATIVE_INFINITY;
                    for(int x=0;x<k;x++)if(active[x]){double candidate=previous[x]+transition[x][y]+e;if(candidate>=probability){probability=candidate;best=x;}}
                    current[y]=probability;back[t][y]=best;
                }
                previous=current;active=allowed;
            }
            double best=Double.NEGATIVE_INFINITY;int last=0;
            for(int y=0;y<k;y++)if(active[y]&&(tags||boundary[y].equals("E")||boundary[y].equals("S")))if(previous[y]>=best){best=previous[y];last=y;}
            int[] route=new int[n];route[n-1]=last;for(int t=n-1;t>0;t--)route[t-1]=back[t][route[t]];
            List<Word> result=new ArrayList<>();int begin=0,next=0;
            for(int i=0;i<n;i++){String b=boundary[route[i]];if(b.equals("B"))begin=i;else if(b.equals("E")){result.add(new Word(s.substring(begin,i+1),pos[route[i]]));next=i+1;}else if(b.equals("S")){result.add(new Word(s.substring(i,i+1),pos[route[i]]));next=i+1;}}
            if(next<n)result.add(new Word(s.substring(next),pos[route[next]]));return result;
        }
    }
}
