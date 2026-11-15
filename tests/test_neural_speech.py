"""Host regression for the real installer and lazy synthesis service; no audio-device claims.

Requires JDK 17 and the checksum-pinned cached Kokoro archive/dependencies.
The official archive is installed into a temporary folder and removed after the test.
"""
from pathlib import Path
import os, shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
STUBS={
'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {boolean getBoolean(String k,boolean d);int getInt(String k,int d);Editor edit();
 interface Editor {Editor putBoolean(String k,boolean v);Editor putInt(String k,int v);boolean commit();}}''',
'android/content/Context.java': '''package android.content;
public class Context {public static final int MODE_PRIVATE=0;public java.io.File dir;
 public Context getApplicationContext(){return this;}public java.io.File getNoBackupFilesDir(){return dir;}
 static final java.util.Map<String,java.util.Map<String,Object>> preferences=new java.util.HashMap<>();
 public SharedPreferences getSharedPreferences(String n,int m){
 final java.util.Map<String,Object> values=preferences.computeIfAbsent(dir.getAbsolutePath()+"/"+n,k->new java.util.HashMap<>());
 return new SharedPreferences(){
 public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
 public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
 public Editor edit(){return new Editor(){public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
 public Editor putInt(String k,int v){values.put(k,v);return this;}public boolean commit(){return true;}};}};}}''',
'android/os/CancellationSignal.java': '''package android.os;
public class CancellationSignal {boolean cancelled;Runnable listener;
 public void cancel(){cancelled=true;if(listener!=null)listener.run();}public boolean isCanceled(){return cancelled;}
 public void throwIfCanceled(){if(cancelled)throw new IllegalStateException("Cancelled");}
 public void setOnCancelListener(Runnable r){listener=r;}}''',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os;import java.util.*;
public class Handler {public Handler(Looper l){}static List<Runnable> posted=new ArrayList<>();
 static Map<Runnable,Long> timers=new LinkedHashMap<>();static long now;
 public boolean post(Runnable r){posted.add(r);return true;}public boolean postDelayed(Runnable r,long d){timers.put(r,now+d);return true;}
 public void removeCallbacks(Runnable r){timers.remove(r);posted.remove(r);}
 public static void flush(){while(!posted.isEmpty())posted.remove(0).run();}
 public static void advance(long d){now+=d;List<Runnable> due=new ArrayList<>();for(Map.Entry<Runnable,Long> e:timers.entrySet())if(e.getValue()<=now)due.add(e.getKey());for(Runnable r:due)if(timers.remove(r)!=null)r.run();flush();}}''',
'android/os/Bundle.java':'''package android.os;public class Bundle {java.util.Map<String,Integer> values=new java.util.HashMap<>();
 public void putInt(String k,int v){values.put(k,v);}public int getInt(String k,int d){return values.getOrDefault(k,d);}}''',
'android/os/Process.java':'package android.os;public class Process {public static int myUid(){return 12345;}}',
'android/media/AudioFormat.java':'package android.media;public class AudioFormat {public static final int ENCODING_PCM_16BIT=2;}',
'android/util/Log.java':'''package android.util;public class Log {
 public static int i(String t,String s){return 0;}public static int w(String t,String s){return 0;}
 public static int w(String t,String s,Throwable e){return 0;}}''',
'android/speech/tts/TextToSpeech.java':'''package android.speech.tts;public class TextToSpeech {
 public static final int SUCCESS=0,ERROR=-1,LANG_AVAILABLE=0,LANG_COUNTRY_AVAILABLE=1,LANG_NOT_SUPPORTED=-2,LANG_MISSING_DATA=-1;
 public static final int ERROR_INVALID_REQUEST=-8,ERROR_NOT_INSTALLED_YET=-9,ERROR_SYNTHESIS=-3,ERROR_OUTPUT=-5;
 public java.util.Set<Voice> voices;public java.util.Set<Voice> getVoices(){return voices;}
 public static int getMaxSpeechInputLength(){return 4000;}}''',
'android/speech/tts/Voice.java':'''package android.speech.tts;public class Voice {
 public static final int QUALITY_HIGH=400,LATENCY_NORMAL=300,LATENCY_VERY_HIGH=500;
 String name;public String getName(){return name;}public Voice(String s,java.util.Locale l,int q,int t,boolean n,java.util.Set<String> f){name=s;}}''',
'android/speech/tts/TextToSpeechService.java':'''package android.speech.tts;
public abstract class TextToSpeechService extends android.content.Context {
 protected abstract String[] onGetLanguage();protected abstract int onIsLanguageAvailable(String a,String b,String c);
 protected abstract int onLoadLanguage(String a,String b,String c);protected abstract void onStop();
 protected abstract void onSynthesizeText(SynthesisRequest r,SynthesisCallback c);
 public java.util.List<Voice> onGetVoices(){return null;}public int onIsValidVoiceName(String n){return 0;}
 public int onLoadVoice(String n){return 0;}public String onGetDefaultVoiceNameFor(String a,String b,String c){return null;}
 public void onDestroy(){}}''',
'android/speech/tts/SynthesisRequest.java':'''package android.speech.tts;public class SynthesisRequest {
 public String text="今天晴，二十六度",voice="zf_001";public int uid=12345,rate=100;public android.os.Bundle params=new android.os.Bundle();
 public CharSequence getCharSequenceText(){return text;}public int getCallerUid(){return uid;}public android.os.Bundle getParams(){return params;}
 public String getVoiceName(){return voice;}public int getSpeechRate(){return rate;}}''',
'android/speech/tts/SynthesisCallback.java':'''package android.speech.tts;public interface SynthesisCallback {
 int start(int rate,int format,int channels);int getMaxBufferSize();int audioAvailable(byte[] b,int o,int n);
 boolean hasFinished();void error(int code);int done();}''',
'dev/weather/oneplus/WeatherRefresh.java':'package dev.weather.oneplus;public class WeatherRefresh {public static boolean networkAllowed(android.content.Context c){return true;}}',
'dev/weather/oneplus/WeatherTibaoModels.java':'''package dev.weather.oneplus;
public final class WeatherTibaoModels {
 public static final String VOICE_PREFIX="weather-local-tibao:";
 public static boolean active,available;public static int chosen=13;
 public static boolean enabled(android.content.Context c){return active;}public static boolean installed(android.content.Context c){return available;}
 public static int preset(android.content.Context c){return chosen;}
 public static java.io.File directory(android.content.Context c){return new java.io.File(c.getNoBackupFilesDir(),"fake-tibao-models");}
}''',
'dev/weather/oneplus/WeatherTibaoEngine.java':'''package dev.weather.oneplus;
public final class WeatherTibaoEngine implements AutoCloseable {
 public static volatile int creates,closes;public static int preset;public static boolean fail;
 public static Runnable during;public static final java.util.List<String> texts=new java.util.ArrayList<>();
 public WeatherTibaoEngine(android.content.Context c,java.io.File models){creates++;}
 public static final class Audio {public final float[] samples={-.5f,0f,.5f};public final int sampleRate=32000;
  public final long frontendMillis=0,semanticMillis=0,vocoderMillis=0;}
 public Audio synthesize(String text,int selected,java.util.function.BooleanSupplier cancelled){
  preset=selected;texts.add(text);if(fail)throw new IllegalStateException("fake engine failure");
  if(during!=null)during.run();if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();return new Audio();}
 public void close(){closes++;}
}''',
'com/k2fsa/sherpa/onnx/OfflineTtsKokoroModelConfig.java':'''package com.k2fsa.sherpa.onnx;
public class OfflineTtsKokoroModelConfig {public String model,voices,tokens,lexicon,data;
 public void setModel(String x){model=x;}public void setVoices(String x){voices=x;}public void setTokens(String x){tokens=x;}
 public void setLexicon(String x){lexicon=x;}public void setDataDir(String x){data=x;}}''',
'com/k2fsa/sherpa/onnx/OfflineTtsModelConfig.java':'''package com.k2fsa.sherpa.onnx;
public class OfflineTtsModelConfig {public OfflineTtsKokoroModelConfig kokoro;public int threads;public String provider;public boolean debug;
 public void setKokoro(OfflineTtsKokoroModelConfig x){kokoro=x;}public void setNumThreads(int x){threads=x;}
 public void setProvider(String x){provider=x;}public void setDebug(boolean x){debug=x;}}''',
'com/k2fsa/sherpa/onnx/OfflineTtsConfig.java':'''package com.k2fsa.sherpa.onnx;
public class OfflineTtsConfig {public OfflineTtsModelConfig model;public int sentences;public String fsts;
 public void setModel(OfflineTtsModelConfig x){model=x;}public void setMaxNumSentences(int x){sentences=x;}public void setRuleFsts(String x){fsts=x;}}''',
'com/k2fsa/sherpa/onnx/OfflineTts.java':'''package com.k2fsa.sherpa.onnx;
public class OfflineTts {
 public static volatile int creates,releases;public static int speaker;public static float speed;public static Runnable during;public static boolean noAudio,fail;public static OfflineTtsConfig config;
 public OfflineTts(Object a,OfflineTtsConfig c){creates++;config=c;}public int sampleRate(){return 24000;}public int numSpeakers(){return 103;}
 public void generateWithCallback(String text,int sid,float rate,kotlin.jvm.functions.Function1<float[],Integer> out){speaker=sid;speed=rate;if(fail)throw new IllegalStateException();
 try{if(out.getClass().getDeclaredMethod("invoke",float[].class).getReturnType()!=Integer.class)throw new AssertionError("JNI callback type");}
 catch(NoSuchMethodException e){throw new AssertionError("JNI needs typed invoke(float[])",e);}
 if(noAudio)return;if(during!=null)during.run();if(out.invoke(new float[]{-2f,-1f,0f,.5f,1f,2f,Float.NaN,Float.POSITIVE_INFINITY})==1)out.invoke(new float[]{.25f});}
 public void release(){releases++;}}''',
'kotlin/jvm/functions/Function1.java':'package kotlin.jvm.functions;public interface Function1<T,R> {R invoke(T value);}',
'dev/weather/oneplus/NeuralSpeechTest.java':'''package dev.weather.oneplus;
import java.io.*;import android.os.*;import android.speech.tts.*;import com.k2fsa.sherpa.onnx.*;
public class NeuralSpeechTest {
 static int count;static void check(boolean x,String message){count++;if(!x)throw new AssertionError(message);}
 static class Output implements SynthesisCallback {int starts,errors,dones,bytes,max=6,audioResult,doneResult,expectedRate=24000,errorCode;boolean finished;java.util.List<Byte> data=new java.util.ArrayList<>();
 public int start(int r,int f,int c){starts++;check(r==expectedRate&&f==2&&c==1,"expected mono PCM sample rate");return 0;}
 public int getMaxBufferSize(){return max;}public int audioAvailable(byte[] b,int o,int n){check(n<=max&&n%2==0,"bounded whole PCM samples");for(int i=0;i<n;i++)data.add(b[o+i]);bytes+=n;return audioResult;}
 public boolean hasFinished(){return finished;}public void error(int e){errors++;errorCode=e;finished=true;}public int done(){dones++;if(doneResult==0)finished=true;return doneResult;}
 int pcm(int i){return (short)((data.get(i*2)&255)|(data.get(i*2+1)<<8));}}
 static void rejects(android.content.Context c,InputStream in,String message)throws Exception {boolean bad=false;try{WeatherNeuralSpeechModels.install(c,in,new CancellationSignal(),(b,t)->{});}catch(Exception e){bad=true;}check(bad,message);}
 static void clean(android.content.Context c){File[] files=new File(c.dir,"weather_speech_models").listFiles();if(files!=null)for(File f:files)check(f.getName().equals(WeatherNeuralSpeechModels.KOKORO),"no orphan staging or archive: "+f);}
 public static void main(String[] args)throws Exception {
 android.content.Context c=new android.content.Context();c.dir=new File(args[1]);
 check(!WeatherNeuralSpeechModels.enabled(c)&&!WeatherNeuralSpeechModels.installed(c),"optional neural model off/absent by default");
 check(WeatherSpeechPreferences.backend(c)==WeatherSpeechPreferences.SYSTEM&&!WeatherSpeechPreferences.local(c),"system backend default does not select private models");
 check(WeatherNeuralSpeechModels.voice(c)==3&&WeatherNeuralSpeechModels.ratePercent(c)==100,"normal default voice/rate");
 boolean rejected=false;try{WeatherNeuralSpeechModels.setEnabled(c,true);}catch(IllegalStateException e){rejected=true;}check(rejected,"cannot enable absent model");
 rejects(c,new ByteArrayInputStream(new byte[64]),"unrecognized archive rejected");clean(c);
 CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();
 try(InputStream input=new FileInputStream(args[0])){try{WeatherNeuralSpeechModels.install(c,input,cancelled,(b,t)->{});}catch(IllegalStateException expected){}}
 check(!WeatherNeuralSpeechModels.installed(c),"cancelled import never published");clean(c);
 try(InputStream input=new FileInputStream(args[0])){WeatherNeuralSpeechModels.install(c,input,new CancellationSignal(),(b,t)->{});}
 check(WeatherNeuralSpeechModels.installed(c),"real SHA-pinned official archive installed");clean(c);
 File model=new File(WeatherNeuralSpeechModels.directory(c),"model.int8.onnx");long time=model.lastModified();
 try(InputStream input=new FileInputStream(args[0])){WeatherNeuralSpeechModels.install(c,input,new CancellationSignal(),(b,t)->{});}
 check(model.lastModified()==time,"same archive preserves mmap-readable model files");clean(c);
 WeatherNeuralSpeechModels.setEnabled(c,true);WeatherNeuralSpeechModels.setVoice(c,53);WeatherNeuralSpeechModels.setRatePercent(c,125);
 check(WeatherNeuralSpeechModels.enabled(c)&&WeatherNeuralSpeechModels.voice(c)==53&&WeatherNeuralSpeechModels.ratePercent(c)==125,"voice/rate/preferences persist");
 check(WeatherSpeechPreferences.backend(c)==WeatherSpeechPreferences.KOKORO,"enabled Kokoro preference migrates to Kokoro route");
 WeatherSpeechPreferences.setBackend(c,WeatherSpeechPreferences.SYSTEM);
 check(WeatherSpeechPreferences.backend(c)==WeatherSpeechPreferences.SYSTEM&&!WeatherSpeechPreferences.local(c),"explicit system selection overrides enabled Kokoro model");
 WeatherSpeechPreferences.setBackend(c,WeatherSpeechPreferences.KOKORO);
 check(WeatherSpeechPreferences.local(c)&&WeatherSpeechPreferences.installed(c),"Kokoro route checks installed Kokoro model");
 WeatherNeuralSpeechService service=new WeatherNeuralSpeechService();service.dir=c.dir;
 check(service.onIsLanguageAvailable("zho","CHN","")==1,"Chinese model available");check(service.onGetVoices().size()==103,"real model speaker list");
 TextToSpeech client=new TextToSpeech();client.voices=new java.util.HashSet<>(service.onGetVoices());
 check(WeatherNeuralSpeechModels.isLocalEngine(client),"private voice contract identifies local engine");
 check(WeatherSpeechPreferences.isPrivateEngine(client),"Kokoro voices satisfy private backend contract");
 client.voices.clear();check(!WeatherNeuralSpeechModels.isLocalEngine(client),"fallback engine voice set rejected");
 check(OfflineTts.creates==0,"discovery/binding never initializes native model");
 SynthesisRequest req=new SynthesisRequest();req.params.putInt("weather_voice_sid",53);req.rate=125;Output out=new Output();service.onSynthesizeText(req,out);Handler.flush();
 check(OfflineTts.creates==1&&out.dones==1&&out.errors==0,"real synthesis request succeeds");
 check(OfflineTts.speaker==53&&OfflineTts.speed==1.25f,"model receives selected voice/rate");
 check(OfflineTts.config.model.threads==2&&"cpu".equals(OfflineTts.config.model.provider)&&OfflineTts.config.sentences==1,"bounded CPU streaming config");
 check(OfflineTts.config.model.kokoro.model.equals(model.getPath())&&OfflineTts.config.fsts.contains("date-zh.fst"),"private model and normalization paths");
 check(out.pcm(0)==-32767&&out.pcm(4)==32767&&out.pcm(5)==32767&&out.pcm(6)==0&&out.pcm(7)==0,"clamped nonfinite-safe PCM");
 req.uid=9;out=new Output();service.onSynthesizeText(req,out);check(out.errors==1&&out.starts==0,"foreign caller denied");req.uid=12345;
 out=new Output();out.audioResult=-1;service.onSynthesizeText(req,out);check(out.errors==1&&out.dones==0,"audio output failure never succeeds");
 out=new Output();out.doneResult=-1;service.onSynthesizeText(req,out);check(out.errors==1,"done output failure reported");
 out=new Output();out.max=1;service.onSynthesizeText(req,out);check(out.errors==1&&out.bytes==0,"invalid tiny output buffer rejected");
 OfflineTts.noAudio=true;out=new Output();service.onSynthesizeText(req,out);check(out.errors==1&&out.dones==0,"empty generation never claims speech");OfflineTts.noAudio=false;
 OfflineTts.during=()->service.onStop();out=new Output();service.onSynthesizeText(req,out);check(out.bytes==0&&out.dones==0,"stop blocks late generated audio");OfflineTts.during=null;
 Handler.flush();out=new Output();service.onSynthesizeText(req,out);Handler.flush();Handler.advance(1001);Thread.sleep(50);
 check(OfflineTts.releases==0,"stale stop cleanup cannot release replacement model");
 Handler.advance(20000);for(int i=0;i<100&&OfflineTts.releases==0;i++)Thread.sleep(10);check(OfflineTts.releases==1,"idle engine releases native weights");
 OfflineTts.fail=true;out=new Output();service.onSynthesizeText(req,out);check(out.errors==1&&out.dones==0,"native synthesis exception reports failure");OfflineTts.fail=false;
 check(WeatherTibaoEngine.creates==0,"Kokoro route never initializes Tibao engine");
 rejected=false;try{WeatherSpeechPreferences.setBackend(c,WeatherSpeechPreferences.TIBAO);}catch(IllegalStateException e){rejected=true;}
 check(rejected&&WeatherSpeechPreferences.backend(c)==WeatherSpeechPreferences.KOKORO,"absent Tibao model cannot replace selected backend");
 WeatherTibaoModels.available=true;WeatherSpeechPreferences.setBackend(c,WeatherSpeechPreferences.TIBAO);
 check(service.onGetVoices().size()==3&&service.onIsLanguageAvailable("eng","USA","")==TextToSpeech.LANG_NOT_SUPPORTED,"Tibao route exposes three Chinese-only presets");
 check(service.onIsLanguageAvailable("zho","CHN","")==TextToSpeech.LANG_COUNTRY_AVAILABLE,"installed Tibao Chinese route available");
 client.voices=new java.util.HashSet<>(service.onGetVoices());
 check(WeatherSpeechPreferences.isPrivateEngine(client)&&!WeatherNeuralSpeechModels.isLocalEngine(client),"Tibao private voices are distinct from Kokoro speaker contract");
 WeatherTibaoModels.chosen=20;
 check(service.onGetDefaultVoiceNameFor("zho","CHN","").equals(WeatherTibaoModels.VOICE_PREFIX+20),"Tibao default uses saved preset");
 check(service.onIsValidVoiceName(WeatherTibaoModels.VOICE_PREFIX+19)==0&&service.onIsValidVoiceName(WeatherTibaoModels.VOICE_PREFIX+99)==-1,"Tibao voice preset validation");
 check(WeatherTibaoEngine.creates==0,"Tibao discovery never initializes native engine");
 int kokoroCreates=OfflineTts.creates,kokoroReleases=OfflineTts.releases;
 req.text="今天上海晴。明天北京有小雨！";req.voice=WeatherTibaoModels.VOICE_PREFIX+20;
 for(int preset:new int[]{13,19,20}){
  req.params.putInt("weather_tibao_preset",preset);WeatherTibaoEngine.texts.clear();out=new Output();out.expectedRate=32000;
  service.onSynthesizeText(req,out);Handler.flush();
  check(out.starts==1&&out.dones==1&&out.errors==0&&out.bytes==12,"Tibao emits both complete sentences through one PCM callback stream");
  check(WeatherTibaoEngine.preset==preset&&WeatherTibaoEngine.texts.equals(java.util.Arrays.asList("今天上海晴。","明天北京有小雨！")),"Tibao request preset and sentence boundaries forwarded");
 }
 check(WeatherTibaoEngine.creates==1&&OfflineTts.creates==kokoroCreates&&OfflineTts.releases==kokoroReleases,"Tibao route reuses its engine without loading Kokoro");
 req.text="今天晴。";req.params.putInt("weather_tibao_preset",99);req.voice=WeatherTibaoModels.VOICE_PREFIX+19;
 out=new Output();out.expectedRate=32000;service.onSynthesizeText(req,out);check(WeatherTibaoEngine.preset==19,"invalid request preset falls back to requested Tibao voice");
 req.voice="unknown";out=new Output();out.expectedRate=32000;service.onSynthesizeText(req,out);
 check(WeatherTibaoEngine.preset==20,"missing Tibao voice falls back to saved preset");
 req.uid=9;out=new Output();out.expectedRate=32000;service.onSynthesizeText(req,out);
 check(out.errorCode==TextToSpeech.ERROR_INVALID_REQUEST&&out.starts==0,"foreign Tibao caller denied before synthesis");req.uid=12345;
 WeatherTibaoModels.available=false;out=new Output();out.expectedRate=32000;service.onSynthesizeText(req,out);
 check(out.errorCode==TextToSpeech.ERROR_NOT_INSTALLED_YET&&out.starts==0,"missing Tibao model never emits audio");WeatherTibaoModels.available=true;
 out=new Output();out.expectedRate=32000;out.audioResult=-1;service.onSynthesizeText(req,out);
 check(out.errorCode==TextToSpeech.ERROR_OUTPUT&&out.dones==0,"Tibao PCM output failure never claims completion");
 WeatherTibaoEngine.during=()->service.onStop();out=new Output();out.expectedRate=32000;service.onSynthesizeText(req,out);
 check(out.starts==0&&out.bytes==0&&out.dones==0&&out.errors==0,"Tibao cancellation blocks late sentence and audio callbacks");WeatherTibaoEngine.during=null;
 int closedAfterCancel=WeatherTibaoEngine.closes;
 Handler.flush();out=new Output();out.expectedRate=32000;service.onSynthesizeText(req,out);Handler.flush();Handler.advance(1001);Thread.sleep(50);
 check(WeatherTibaoEngine.closes==closedAfterCancel,"stale Tibao stop timer cannot close replacement engine");
 Handler.advance(20000);for(int i=0;i<100&&WeatherTibaoEngine.closes==closedAfterCancel;i++)Thread.sleep(10);
 check(WeatherTibaoEngine.closes==closedAfterCancel+1,"idle Tibao engine releases its resources");
 WeatherTibaoEngine.fail=true;out=new Output();out.expectedRate=32000;service.onSynthesizeText(req,out);
 check(out.errorCode==TextToSpeech.ERROR_SYNTHESIS&&out.dones==0,"Tibao engine exception reports synthesis failure");WeatherTibaoEngine.fail=false;
 service.onDestroy();System.out.println("PASS: "+count+" neural installer/service assertions (Kokoro/Tibao engines are deterministic fakes)");
 }}''',
}
def main():
    binpath=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else None
    suffix='.exe' if os.name=='nt' else ''
    javac=str(binpath/('javac'+suffix)) if binpath else shutil.which('javac')
    java=str(binpath/('java'+suffix)) if binpath else shutil.which('java')
    compress=ROOT/'.build/dependencies/commons-compress-1.21.jar'
    archive=ROOT/'.build/downloads/kokoro.tar.bz2'
    if not compress.exists() or not archive.exists():raise SystemExit('Cache pinned speech archive/dependencies before running installer regression')
    with tempfile.TemporaryDirectory(prefix='weather-neural-tests-',dir=ROOT/'.build') as tmp:
        path=Path(tmp)
        for name,content in STUBS.items():
            target=path/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content,encoding='utf8')
        classes=path/'classes';classes.mkdir();sources=[str(p) for p in path.rglob('*.java')]+[str(ROOT/'port'/n) for n in ('WeatherNeuralSpeechModels.java','WeatherNeuralSpeechService.java','WeatherSpeechPreferences.java')]
        subprocess.run([javac,'--release','8','-encoding','UTF-8','-cp',str(compress),'-d',str(classes),*sources],check=True)
        modelroot=path/'data';modelroot.mkdir()
        subprocess.run([java,'-cp',os.pathsep.join(map(str,(classes,compress))),'dev.weather.oneplus.NeuralSpeechTest',str(archive),str(modelroot)],check=True)
if __name__=='__main__':main()
