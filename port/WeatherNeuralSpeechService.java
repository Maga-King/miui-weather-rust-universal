package dev.weather.oneplus;

import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Private standard TTS service. Model/ONNX initialization happens only during synthesis. */
public final class WeatherNeuralSpeechService extends TextToSpeechService {
    private final Object nativeLock=new Object();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService cleanup=Executors.newSingleThreadExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private volatile boolean destroyed;
    private volatile String[] language={"zho","CHN",""};
    private OfflineTts engine;
    private WeatherTibaoEngine tibao;
    private Runnable idleRelease;

    @Override protected String[] onGetLanguage(){return language.clone();}
    @Override protected int onIsLanguageAvailable(String lang,String country,String variant){
        boolean chinese="zh".equals(lang)||"zho".equals(lang)||"chi".equals(lang);
        if(!chinese&&!("en".equals(lang)||"eng".equals(lang)))return TextToSpeech.LANG_NOT_SUPPORTED;
        if(WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.TIBAO&&!chinese)return TextToSpeech.LANG_NOT_SUPPORTED;
        return WeatherSpeechPreferences.installed(this)?TextToSpeech.LANG_COUNTRY_AVAILABLE:TextToSpeech.LANG_MISSING_DATA;
    }
    @Override protected int onLoadLanguage(String lang,String country,String variant){
        int result=onIsLanguageAvailable(lang,country,variant);
        if(result>=TextToSpeech.LANG_AVAILABLE)language=new String[]{lang,country,variant};return result;
    }
    @Override public List<Voice> onGetVoices(){
        if(WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.TIBAO){
            if(!WeatherTibaoModels.installed(this))return Collections.emptyList();
            List<Voice> result=new ArrayList<>();
            for(int preset:new int[]{13,19,20})result.add(new Voice(WeatherTibaoModels.VOICE_PREFIX+preset,Locale.SIMPLIFIED_CHINESE,
                    Voice.QUALITY_HIGH,Voice.LATENCY_VERY_HIGH,false,Collections.emptySet()));
            return result;
        }
        if(!WeatherNeuralSpeechModels.installed(this))return Collections.emptyList();
        List<Voice> out=new ArrayList<>();
        for(String name:WeatherNeuralSpeechModels.VOICE_NAMES)out.add(new Voice(WeatherNeuralSpeechModels.VOICE_PREFIX+name,name.startsWith("z")?Locale.SIMPLIFIED_CHINESE:Locale.US,
                Voice.QUALITY_HIGH,Voice.LATENCY_NORMAL,false,Collections.emptySet()));
        return out;
    }
    @Override public int onIsValidVoiceName(String name){return sid(name)>=0||tibaoPreset(name)>=0?TextToSpeech.SUCCESS:TextToSpeech.ERROR;}
    @Override public int onLoadVoice(String name){return onIsValidVoiceName(name);}
    @Override public String onGetDefaultVoiceNameFor(String lang,String country,String variant){
        if(WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.TIBAO)return WeatherTibaoModels.VOICE_PREFIX+WeatherTibaoModels.preset(this);
        return WeatherNeuralSpeechModels.VOICE_PREFIX+("en".equals(lang)||"eng".equals(lang)?"af_maple":"zf_001");
    }
    static int sid(String name){if(name!=null&&name.startsWith(WeatherNeuralSpeechModels.VOICE_PREFIX))name=name.substring(WeatherNeuralSpeechModels.VOICE_PREFIX.length());
        for(int i=0;i<WeatherNeuralSpeechModels.VOICE_NAMES.length;i++)if(WeatherNeuralSpeechModels.VOICE_NAMES[i].equals(name))return i;return -1;}
    static int tibaoPreset(String name){
        if(name==null||!name.startsWith(WeatherTibaoModels.VOICE_PREFIX))return -1;
        try{int id=Integer.parseInt(name.substring(WeatherTibaoModels.VOICE_PREFIX.length()));return id==13||id==19||id==20?id:-1;}
        catch(NumberFormatException ignored){return -1;}
    }
    @Override protected void onStop(){int token=generation.incrementAndGet();scheduleRelease(token,1000);}
    private void scheduleRelease(int token,long delay){
        // Synthesis runs off the UI thread. Only the main thread owns the timer;
        // a stopped old request must not overwrite a newer request's release timer.
        main.post(()->{
            if(destroyed||generation.get()!=token)return;
            if(idleRelease!=null)main.removeCallbacks(idleRelease);
            idleRelease=()->{if(!destroyed&&generation.get()==token)cleanup.execute(()->{
                synchronized(nativeLock){if(generation.get()==token)release();}
            });};
            main.postDelayed(idleRelease,delay);
        });
    }
    @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback callback){
        // Exported=false and this UID check prevent other apps driving the bundled model.
        if(request.getCallerUid()!=android.os.Process.myUid()){callback.error(TextToSpeech.ERROR_INVALID_REQUEST);return;}
        String text=request.getCharSequenceText().toString().trim();
        if(text.isEmpty()||text.length()>TextToSpeech.getMaxSpeechInputLength()){callback.error(TextToSpeech.ERROR_INVALID_REQUEST);return;}
        if(WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.TIBAO){synthesizeTibao(text,request,callback);return;}
        int requestedSid=request.getParams().getInt("weather_voice_sid",-1);
        final int speaker=requestedSid>=0&&requestedSid<WeatherNeuralSpeechModels.VOICE_NAMES.length?requestedSid:
                Math.max(0,sid(request.getVoiceName()));
        final int active=generation.incrementAndGet();
        synchronized(nativeLock){
            try {
                if(destroyed||generation.get()!=active)return;
                if(!WeatherNeuralSpeechModels.installed(this)){callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET);return;}
                if(engine==null)engine=create();
                if(destroyed||generation.get()!=active)return;
                final int rate=engine.sampleRate();
                if(rate<8000||rate>96000||speaker>=engine.numSpeakers()){callback.error(TextToSpeech.ERROR_SYNTHESIS);return;}
                if(callback.start(rate,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS){
                    if(!callback.hasFinished())callback.error(TextToSpeech.ERROR_OUTPUT);return;
                }
                final int chunkBytes=Math.min(32768,callback.getMaxBufferSize())&~1;
                if(chunkBytes<2){callback.error(TextToSpeech.ERROR_OUTPUT);return;}
                final byte[] bytes=new byte[chunkBytes];
                final boolean[] failed={false},emitted={false};
                float speed=Math.max(.5f,Math.min(2f,request.getSpeechRate()/100f));
                // Return 0 at the next chunk after stop; no late buffer can play a replaced utterance.
                // JNI resolves invoke(float[]) -> Integer. A Java lambda only exposes
                // erased invoke(Object), so keep a concrete typed method and bridge.
                engine.generateWithCallback(text,speaker,speed,new kotlin.jvm.functions.Function1<float[],Integer>(){
                    @Override public Integer invoke(float[] samples){
                    for(int offset=0;offset<samples.length;){
                        if(destroyed||generation.get()!=active)return 0;
                        int count=Math.min(bytes.length/2,samples.length-offset);
                        for(int i=0;i<count;i++){
                            float sample=samples[offset+i];
                            int value=Math.round(Math.max(-1f,Math.min(1f,Float.isFinite(sample)?sample:0))*32767f);
                            bytes[i*2]=(byte)value;bytes[i*2+1]=(byte)(value>>8);
                        }
                        if(callback.audioAvailable(bytes,0,count*2)!=TextToSpeech.SUCCESS){failed[0]=true;return 0;}
                        emitted[0]=true;
                        offset+=count;
                    }return 1;
                    }
                });
                if(!destroyed&&generation.get()==active&&!callback.hasFinished()){
                    if(failed[0])callback.error(TextToSpeech.ERROR_OUTPUT);
                    else if(!emitted[0])callback.error(TextToSpeech.ERROR_SYNTHESIS);
                    else if(callback.done()!=TextToSpeech.SUCCESS&&!callback.hasFinished())callback.error(TextToSpeech.ERROR_OUTPUT);
                }
            }catch(RuntimeException|LinkageError error){
                Log.w("WeatherSpeech","Local neural synthesis failed: "+error.getClass().getSimpleName());
                release();if(!destroyed&&generation.get()==active&&!callback.hasFinished())callback.error(TextToSpeech.ERROR_SYNTHESIS);
            }finally{if(!destroyed)scheduleRelease(active,20000);}
        }
    }
    private void synthesizeTibao(String text,SynthesisRequest request,SynthesisCallback callback){
        final int active=generation.incrementAndGet();
        int chosen=request.getParams().getInt("weather_tibao_preset",-1);
        if(chosen!=13&&chosen!=19&&chosen!=20)chosen=tibaoPreset(request.getVoiceName());
        final int preset=chosen==13||chosen==19||chosen==20?chosen:WeatherTibaoModels.preset(this);
        synchronized(nativeLock){
            try{
                if(destroyed||generation.get()!=active)return;
                if(!WeatherTibaoModels.installed(this)){callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET);return;}
                if(engine!=null){engine.release();engine=null;}
                if(tibao==null)tibao=new WeatherTibaoEngine(this,WeatherTibaoModels.directory(this));
                final int chunkBytes=Math.min(32768,callback.getMaxBufferSize())&~1;
                if(chunkBytes<2){callback.error(TextToSpeech.ERROR_OUTPUT);return;}
                byte[] bytes=new byte[chunkBytes];boolean begun=false;
                for(String sentence:sentences(text)){
                    if(destroyed||generation.get()!=active)return;
                    WeatherTibaoEngine.Audio audio=tibao.synthesize(sentence,preset,()->destroyed||generation.get()!=active);
                    Log.i("WeatherSpeech","Tribbie preset="+preset+" frontend_ms="+audio.frontendMillis+" semantic_ms="+audio.semanticMillis+
                            " vocoder_ms="+audio.vocoderMillis+" samples="+audio.samples.length);
                    if(destroyed||generation.get()!=active)return;
                    if(!begun){
                        if(callback.start(audio.sampleRate,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS){callback.error(TextToSpeech.ERROR_OUTPUT);return;}
                        begun=true;
                    }
                    // Constant voice-volume matching. No EQ/pitch changes;
                    // cap the gain so a peak never clips after conversion.
                    double sum=0;float peak=0;
                    for(float sample:audio.samples){sum+=(double)sample*sample;peak=Math.max(peak,Math.abs(sample));}
                    double rms=Math.sqrt(sum/audio.samples.length),gain=Math.min(rms>0?.1/rms:1,peak>0?.95/peak:1);
                    for(int offset=0;offset<audio.samples.length;){
                        if(destroyed||generation.get()!=active)return;
                        int count=Math.min(bytes.length/2,audio.samples.length-offset);
                        for(int i=0;i<count;i++){
                            int value=(int)Math.round(Math.max(-1,Math.min(1,audio.samples[offset+i]*gain))*32767);
                            bytes[2*i]=(byte)value;bytes[2*i+1]=(byte)(value>>8);
                        }
                        if(callback.audioAvailable(bytes,0,count*2)!=TextToSpeech.SUCCESS){callback.error(TextToSpeech.ERROR_OUTPUT);return;}
                        offset+=count;
                    }
                }
                if(!destroyed&&generation.get()==active&&!callback.hasFinished()){
                    if(begun)callback.done();else callback.error(TextToSpeech.ERROR_SYNTHESIS);
                }
            }catch(Exception|LinkageError error){
                Log.w("WeatherSpeech","Tribbie synthesis failed: "+error.getClass().getSimpleName(),error);
                release();if(!destroyed&&generation.get()==active&&!callback.hasFinished())callback.error(TextToSpeech.ERROR_SYNTHESIS);
            }finally{if(!destroyed)scheduleRelease(active,20000);}
        }
    }
    static List<String> sentences(String text){
        List<String> result=new ArrayList<>();StringBuilder current=new StringBuilder();
        for(int offset=0;offset<text.length();){
            int cp=text.codePointAt(offset);offset+=Character.charCount(cp);current.appendCodePoint(cp);
            if(cp=='。'||cp=='！'||cp=='？'||cp=='!'||cp=='?'||cp=='\n'){
                String sentence=current.toString().trim();if(!sentence.isEmpty())result.add(sentence);current.setLength(0);
            }
        }
        String rest=current.toString().trim();if(!rest.isEmpty())result.add(rest);return result;
    }
    private OfflineTts create(){
        File directory=WeatherNeuralSpeechModels.directory(this);
        OfflineTtsKokoroModelConfig kokoro=new OfflineTtsKokoroModelConfig();
        kokoro.setModel(new File(directory,"model.int8.onnx").getPath());
        kokoro.setVoices(new File(directory,"voices.bin").getPath());
        kokoro.setTokens(new File(directory,"tokens.txt").getPath());
        kokoro.setLexicon(new File(directory,"lexicon-us-en.txt").getPath()+","+new File(directory,"lexicon-zh.txt").getPath());
        kokoro.setDataDir(new File(directory,"espeak-ng-data").getPath());
        OfflineTtsModelConfig model=new OfflineTtsModelConfig();model.setKokoro(kokoro);
        model.setNumThreads(2);model.setProvider("cpu");model.setDebug(false);
        OfflineTtsConfig config=new OfflineTtsConfig();config.setModel(model);config.setMaxNumSentences(1);
        config.setRuleFsts(new File(directory,"number-zh.fst").getPath()+","+new File(directory,"date-zh.fst").getPath()+","+new File(directory,"phone-zh.fst").getPath());
        return new OfflineTts(null,config);
    }
    private void release(){if(engine!=null){engine.release();engine=null;}if(tibao!=null){try{tibao.close();}catch(Exception ignored){}tibao=null;}}
    @Override public void onDestroy(){destroyed=true;generation.incrementAndGet();if(idleRelease!=null)main.removeCallbacks(idleRelease);
        cleanup.execute(()->{synchronized(nativeLock){release();}});cleanup.shutdown();super.onDestroy();}
}
