package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;

/** Selected standard/private speech backend. Unselected models are never initialized. */
public final class WeatherSpeechPreferences {
    public static final int SYSTEM=0,KOKORO=1,TIBAO=2;
    private static final String PREFS="weather_speech_backend_v1";
    private WeatherSpeechPreferences() {}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static int backend(Context c){
        int migrated=WeatherTibaoModels.enabled(c)?TIBAO:WeatherNeuralSpeechModels.enabled(c)?KOKORO:SYSTEM;
        int value=prefs(c).getInt("backend",migrated);
        return value>=SYSTEM&&value<=TIBAO?value:SYSTEM;
    }
    public static boolean local(Context c){return backend(c)!=SYSTEM;}
    public static boolean installed(Context c){return backend(c)==TIBAO?WeatherTibaoModels.installed(c):WeatherNeuralSpeechModels.installed(c);}
    public static void setBackend(Context c,int value){
        if(value<SYSTEM||value>TIBAO)throw new IllegalArgumentException("Unknown speech backend");
        if(value==TIBAO&&!WeatherTibaoModels.installed(c))throw new IllegalStateException("Import the Tribbie model first");
        if(value==KOKORO&&!WeatherNeuralSpeechModels.installed(c))throw new IllegalStateException("Install the Kokoro model first");
        if(!prefs(c).edit().putInt("backend",value).commit())throw new IllegalStateException("Could not save speech backend");
    }
    public static boolean isPrivateEngine(android.speech.tts.TextToSpeech engine){
        java.util.Set<android.speech.tts.Voice> voices=engine.getVoices();
        if(voices==null||voices.isEmpty())return false;
        for(android.speech.tts.Voice voice:voices){
            String name=voice.getName();
            if(name==null||(!name.startsWith(WeatherNeuralSpeechModels.VOICE_PREFIX)&&!name.startsWith(WeatherTibaoModels.VOICE_PREFIX)))return false;
        }
        return true;
    }
}
