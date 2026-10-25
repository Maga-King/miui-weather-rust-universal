"""Run speech lifecycle/codec regression tests on the JVM with deterministic Android fakes.

Requires JDK 17 (JAVA_HOME). Does not install an APK or need a device.
The production bridge and Codec are compiled unchanged.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'android/content/Context.java': '''package android.content;
public class Context {
 public final android.media.AudioManager audio = new android.media.AudioManager();
 public Context getApplicationContext(){return this;}
 public <T> T getSystemService(Class<T> t){return t.cast(audio);}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
}''',
    'android/app/Activity.java': 'package android.app; public class Activity extends android.content.Context {}',
    'android/content/res/Resources.java': '''package android.content.res;
public class Resources { public Configuration getConfiguration(){return new Configuration();} }''',
    'android/content/res/Configuration.java': '''package android.content.res;
public class Configuration { public android.os.LocaleList getLocales(){return new android.os.LocaleList();} }''',
    'android/os/LocaleList.java': '''package android.os;
public class LocaleList { public java.util.Locale get(int i){return java.util.Locale.US;} }''',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Bundle.java': 'package android.os; public class Bundle {public void putInt(String k,int v){}}',
    'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 static final List<Runnable> posted=new ArrayList<>();
 static final Map<Runnable,Long> timed=new LinkedHashMap<>(); static long now;
 public Handler(Looper l){}
 public boolean post(Runnable r){posted.add(r);return true;}
 public boolean postDelayed(Runnable r,long delay){timed.put(r,now+delay);return true;}
 public void removeCallbacks(Runnable r){posted.remove(r);timed.remove(r);}
 public static void flush(){while(!posted.isEmpty()){Runnable r=posted.remove(0);r.run();}}
 public static void advance(long ms){now+=ms;List<Runnable> due=new ArrayList<>();
  for(Map.Entry<Runnable,Long> e:timed.entrySet())if(e.getValue()<=now)due.add(e.getKey());
  for(Runnable r:due)if(timed.remove(r)!=null)r.run();flush();}
 public static void reset(){posted.clear();timed.clear();now=0;}
}''',
    'android/media/AudioAttributes.java': '''package android.media;
public class AudioAttributes {public static final int USAGE_MEDIA=1,CONTENT_TYPE_SPEECH=1;
 public static class Builder {public Builder setUsage(int i){return this;}
 public Builder setContentType(int i){return this;} public AudioAttributes build(){return new AudioAttributes();}}}''',
    'android/media/AudioManager.java': '''package android.media;
public class AudioManager {
 public static final int AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK=3,AUDIOFOCUS_REQUEST_GRANTED=1,STREAM_MUSIC=3;
 public interface OnAudioFocusChangeListener {void onAudioFocusChange(int change);}
 public int result=1,abandoned; public AudioFocusRequest last;
 public int requestAudioFocus(AudioFocusRequest f){last=f;return result;}
 public int abandonAudioFocusRequest(AudioFocusRequest f){abandoned++;return 1;}
}''',
    'android/media/AudioFocusRequest.java': '''package android.media;
public class AudioFocusRequest {
 public AudioManager.OnAudioFocusChangeListener listener;
 public static class Builder {AudioFocusRequest f=new AudioFocusRequest();public Builder(int i){}
 public Builder setAudioAttributes(AudioAttributes a){return this;}
 public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener l,android.os.Handler h){f.listener=l;return this;}
 public AudioFocusRequest build(){return f;}}
}''',
    'android/util/Log.java': '''package android.util; public class Log {
 public static int i(String t,String s){return 0;}public static int w(String t,String s){return 0;}
 public static int w(String t,String s,Throwable e){return 0;}}''',
    'android/widget/Toast.java': '''package android.widget; public class Toast {
 public static final int LENGTH_SHORT=0,LENGTH_LONG=1;
 public static Toast makeText(android.content.Context c,CharSequence t,int d){return new Toast();}
 public void show(){} }''',
    'android/speech/tts/UtteranceProgressListener.java': '''package android.speech.tts;
public abstract class UtteranceProgressListener {
 public abstract void onStart(String id);public abstract void onDone(String id);public abstract void onError(String id);
 public void onError(String id,int code){onError(id);}public void onStop(String id,boolean interrupted){}
}''',
    'android/speech/tts/TextToSpeech.java': '''package android.speech.tts;
public class TextToSpeech {
 public static final int SUCCESS=0,LANG_AVAILABLE=0,QUEUE_FLUSH=0;
 public static TextToSpeech last;public static boolean throwOnCreate;
 public interface OnInitListener {void onInit(int status);}
 public static class Engine {public static final String KEY_PARAM_STREAM="stream";}
 public final OnInitListener init;public UtteranceProgressListener progress;
 public int languageResult=0,speakResult=0,speaks,stops,shutdowns;
 public String text,id;public java.util.Locale language;
 public TextToSpeech(android.content.Context c,OnInitListener l){if(throwOnCreate)throw new IllegalStateException();init=l;last=this;}
 public static int getMaxSpeechInputLength(){return 4000;}
 public int setAudioAttributes(android.media.AudioAttributes a){return 0;}
 public int setOnUtteranceProgressListener(UtteranceProgressListener p){progress=p;return 0;}
 public int setLanguage(java.util.Locale l){language=l;return languageResult;}
 public int speak(String t,int queue,android.os.Bundle params,String uid){text=t;id=uid;speaks++;return speakResult;}
 public int stop(){stops++;return 0;}public void shutdown(){shutdowns++;}
}''',
    'org/json/JSONObject.java': r'''package org.json;
import java.util.*;
public class JSONObject {
 public static final Object NULL=new Object();private final Map<String,Object> data=new LinkedHashMap<>();
 public JSONObject put(String k,Object v){data.put(k,v);return this;}public Object opt(String k){return data.get(k);}
 public String toString(){StringBuilder b=new StringBuilder("{");
  for(Map.Entry<String,Object> e:data.entrySet()){if(b.length()>1)b.append(',');
   b.append('\"').append(e.getKey()).append("\":");Object v=e.getValue();
   if(v==null||v==NULL)b.append("null");else b.append('\"').append(v).append('\"');}
  return b.append('}').toString();}
}''',
    'dev/weather/oneplus/SpeechTest.java': '''package dev.weather.oneplus;
import android.app.Activity;import android.os.Handler;import android.speech.tts.TextToSpeech;
import android.media.AudioFocusRequest;import java.util.*;import java.nio.charset.StandardCharsets;
public final class SpeechTest {
 static int assertions;
 static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
 static class Reply implements AndroidWeatherSpeech.Result {
  int successes,errors;String code;public void success(Object v){successes++;}
  public void error(String c,String m){errors++;code=c;}
 }
 static class Fixture {
  Activity app=new Activity();List<byte[]> events=new ArrayList<>();
  AndroidWeatherSpeech bridge=new AndroidWeatherSpeech(app,(c,b)->{check(c.equals("weather_tts_channel"),"event channel");events.add(b);});
  Fixture(){bridge.onResume();}
  Reply call(String method,Object args,boolean json){Reply r=new Reply();
   check(bridge.handle("weather_method_channel",method,args,json,r),"method handled");return r;}
  Reply start(String text){return call("tts_start_speak",Collections.singletonMap("tts_speak_text",text),true);}
  void ready(){Reply r=call("tts_init",null,true);TextToSpeech.last.init.onInit(0);Handler.flush();check(r.successes==1,"init completion");}
  String event(int index){return new String(events.get(index),StandardCharsets.UTF_8);}
  void close(){bridge.onDestroy();Handler.reset();}
 }
 public static void main(String[] args){
  Fixture f=new Fixture();Reply init=f.call("tts_init",null,true);
  check(init.successes==0,"init must wait for real callback");TextToSpeech engine=TextToSpeech.last;
  engine.init.onInit(0);Handler.flush();check(init.successes==1&&init.errors==0,"successful init exactly once");
  Reply start=f.start("今天晴，二十六摄氏度。");check(start.successes==1,"speech enqueued");
  check(engine.text.equals("今天晴，二十六摄氏度。"),"speak original selected-city text");
  check(engine.language.equals(Locale.SIMPLIFIED_CHINESE),"Chinese voice");
  check(f.events.isEmpty(),"no fake playback start");String first=engine.id;
  engine.progress.onStart(first);Handler.flush();check(f.event(0).contains("on_start"),"actual playback starts UI");
  Handler.advance(16000);check(f.events.size()==1,"start callback cancels timeout");
  engine.progress.onDone(first);Handler.flush();check(f.event(1).contains("on_done"),"completion resets UI");
  engine.progress.onError(first);Handler.flush();check(f.events.size()==2,"late completion ignored");
  f.close();check(engine.shutdowns==1,"destroy releases engine exactly once");

  f=new Fixture();Reply pending=f.start("Test weather");engine=TextToSpeech.last;
  f.call("tts_stop_speak",null,true);check(pending.errors==1,"stop cancels pending speech");
  engine.init.onInit(0);Handler.flush();check(engine.speaks==0&&pending.successes==0,"late init cannot speak after stop");f.close();

  f=new Fixture();pending=f.start("Test weather");engine=TextToSpeech.last;
  f.bridge.onStop();engine.init.onInit(0);Handler.flush();
  check(engine.speaks==0&&pending.errors==1,"background cancels pending narration");
  start=f.start("Late Dart continuation");check(start.errors==1&&TextToSpeech.last==engine,"late request after stop cannot reconnect or speak");
  f.bridge.onResume();f.ready();check(TextToSpeech.last!=engine,"visible activity can reconnect");f.close();

  f=new Fixture();init=f.call("tts_init",null,true);engine=TextToSpeech.last;
  Handler.advance(12001);check(init.errors==1&&engine.shutdowns==1,"timeout releases init waiters");
  engine.init.onInit(0);Handler.flush();check(init.successes==0,"late init success after timeout ignored");
  f.ready();check(TextToSpeech.last!=engine,"failed initialization can retry");f.close();

  f=new Fixture();f.ready();engine=TextToSpeech.last;
  f.start("First narration");AudioFocusRequest old=f.app.audio.last;String oldId=engine.id;
  f.start("Second narration");String newId=engine.id;int before=engine.stops;
  old.listener.onAudioFocusChange(-1);check(engine.stops==before,"stale focus loss cannot stop new narration");
  int count=f.events.size();engine.progress.onDone(oldId);Handler.flush();
  check(f.events.size()==count,"old utterance cannot finish new narration");
  engine.progress.onStart(newId);Handler.flush();check(f.event(f.events.size()-1).contains("on_start"),"new utterance remains active");
  f.app.audio.last.listener.onAudioFocusChange(-1);check(f.event(f.events.size()-1).contains("on_done"),"real focus loss resets UI");f.close();

  f=new Fixture();f.ready();engine=TextToSpeech.last;engine.languageResult=-2;
  start=f.start("今天晴");check(start.errors==1&&engine.speaks==0,"missing Chinese voice does not claim playback");f.close();

  f=new Fixture();f.ready();engine=TextToSpeech.last;f.app.audio.result=0;
  start=f.start("Test weather");check(start.errors==1&&engine.speaks==0,"denied audio focus does not play");f.close();

  f=new Fixture();f.ready();engine=TextToSpeech.last;engine.speakResult=-1;
  start=f.start("Test weather");check(start.errors==1&&engine.shutdowns==1,"failed queue releases engine");f.close();

  f=new Fixture();f.ready();engine=TextToSpeech.last;f.start("Test weather");
  Handler.advance(15001);check(f.event(f.events.size()-1).contains("on_error"),"hung synthesis resets UI");f.close();

  f=new Fixture();f.ready();engine=TextToSpeech.last;
  start=f.call("tts_start_speak",Collections.singletonMap("tts_speak_text","Test weather"),false);
  engine.progress.onStart(engine.id);Handler.flush();
  java.nio.ByteBuffer bytes=Codec.buffer(f.events.get(0));
  check("on_start".equals(Codec.read(bytes))&&Codec.read(bytes)==null&&!bytes.hasRemaining(),"StandardMethodCodec callback has no result envelope");f.close();

  f=new Fixture();start=f.start("   ");check(start.errors==1,"blank text rejected");
  check(!f.bridge.handle("other_channel","tts_init",null,true,new Reply()),"unrelated channels untouched");f.close();
  System.out.println("PASS: "+assertions+" assertions covering speech callbacks, cancellation, retries, audio focus and codecs");
 }
}''',
}


def main():
    java_home = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(java_home / ('javac' + suffix)) if java_home else shutil.which('javac')
    java = str(java_home / ('java' + suffix)) if java_home else shutil.which('java')
    if not javac or not java:
        raise SystemExit('Set JAVA_HOME to a JDK before running speech tests')
    with tempfile.TemporaryDirectory(prefix='weather-speech-tests-') as tmp:
        root = Path(tmp)
        for name, content in SOURCES.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        sources = [str(p) for p in root.rglob('*.java')]
        sources += [str(ROOT / 'port' / name) for name in ('AndroidWeatherSpeech.java', 'Codec.java')]
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run([javac, '--release', '8', '-encoding', 'UTF-8', '-d', str(classes), *sources], check=True)
        subprocess.run([java, '-cp', str(classes), 'dev.weather.oneplus.SpeechTest'], check=True)


if __name__ == '__main__':
    main()
