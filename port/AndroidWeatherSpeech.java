package dev.weather.oneplus;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Standard Android TTS for the original temperature tap, without a vendor service. */
public final class AndroidWeatherSpeech {
    public interface Result {
        void success(Object value);
        void error(String code, String message);
    }
    public interface Sender { void send(String channel, byte[] message); }
    private static final String TAG = "WeatherSpeech";
    private static final String EVENTS = "weather_tts_channel";
    private final Context context;
    private final Sender sender;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final List<Result> waiting = new ArrayList<>();
    private TextToSpeech engine;
    private AudioFocusRequest focus;
    private boolean ready, initializing, destroyed, foreground, jsonEvents = true;
    private int generation, serial, focusGeneration;
    private String utterance;
    private Runnable initTimeout, startTimeout;

    public AndroidWeatherSpeech(Activity activity, Sender sender) {
        context = activity.getApplicationContext();
        this.sender = sender;
        audio = context.getSystemService(AudioManager.class);
    }

    /** Runs on the UI thread; init replies only after the engine really is ready. */
    public boolean handle(String channel, String method, Object args, boolean json, Result reply) {
        if (!"weather_method_channel".equals(channel)) return false;
        if (!"tts_init".equals(method) && !"tts_start_speak".equals(method)
                && !"tts_stop_speak".equals(method)) return false;
        jsonEvents = json;
        if (destroyed) { reply.error("tts_closed", "Weather activity has closed"); return true; }
        if (!foreground && !"tts_stop_speak".equals(method)) {
            reply.error("tts_cancelled", "Weather is no longer visible"); event("on_error"); return true;
        }
        if ("tts_init".equals(method)) initialize(reply);
        else if ("tts_stop_speak".equals(method)) { stop(); cancelInitialization(); reply.success(null); }
        else {
            Object value = args instanceof JSONObject ? ((JSONObject)args).opt("tts_speak_text")
                : args instanceof Map ? ((Map<?, ?>)args).get("tts_speak_text") : null;
            if (!(value instanceof String) || ((String)value).trim().isEmpty()) {
                reply.error("tts_empty_text", "No weather narration text was supplied");
                event("on_error");
            } else {
                final String text = ((String)value).trim();
                if (text.length() > TextToSpeech.getMaxSpeechInputLength()) {
                    reply.error("tts_text_too_long", "Weather narration exceeds the system speech limit");
                    event("on_error");
                } else initialize(new Result() {
                    public void success(Object unused) { speak(text, reply); }
                    public void error(String code, String message) { reply.error(code, message); }
                });
            }
        }
        return true;
    }

    private void initialize(Result reply) {
        if (ready) { reply.success(null); return; }
        waiting.add(reply);
        if (initializing) return;
        initializing = true;
        final int attempt = ++generation;
        initTimeout = () -> {
            if (!destroyed && initializing && generation == attempt)
                initFailed("tts_timeout", "系统语音引擎响应超时，请稍后重试。");
        };
        main.postDelayed(initTimeout, 12000);
        try {
            // Always post: some engines invoke onInit before the constructor returns.
            engine = new TextToSpeech(context, status -> main.post(() -> initialized(attempt, status)));
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot connect to system TTS", error);
            initFailed("tts_unavailable", "系统语音引擎不可用，请在系统设置中安装或启用文字转语音引擎。");
        }
    }

    private void initialized(int attempt, int status) {
        if (destroyed || attempt != generation || !initializing) return;
        if (status != TextToSpeech.SUCCESS || engine == null) {
            initFailed("tts_unavailable", "系统语音引擎不可用，请在系统设置中安装或启用文字转语音引擎。");
            return;
        }
        try {
            engine.setAudioAttributes(attributes);
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { main.post(() -> {
                    if (current(id)) { cancelStartTimeout(); event("on_start"); }
                }); }
                @Override public void onDone(String id) { main.post(() -> ended(id, false)); }
                @Override public void onError(String id) { main.post(() -> ended(id, true)); }
                @Override public void onError(String id, int code) {
                    Log.w(TAG, "System TTS failed, code=" + code);
                    main.post(() -> ended(id, true));
                }
                @Override public void onStop(String id, boolean interrupted) { main.post(() -> ended(id, false)); }
            });
            ready = true; initializing = false;
            main.removeCallbacks(initTimeout); initTimeout = null;
            List<Result> callbacks = drain();
            Log.i(TAG, "System speech initialized");
            for (Result result : callbacks) result.success(null);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot configure system TTS", error);
            initFailed("tts_unavailable", "系统语音引擎初始化失败，请稍后重试。");
        }
    }

    private void initFailed(String code, String message) {
        ++generation; ready = false; initializing = false;
        if (initTimeout != null) { main.removeCallbacks(initTimeout); initTimeout = null; }
        releaseEngine();
        List<Result> callbacks = drain();
        for (Result result : callbacks) result.error(code, message);
        event("on_error");
        if (!destroyed) Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private List<Result> drain() {
        List<Result> copy = new ArrayList<>(waiting); waiting.clear(); return copy;
    }

    private void speak(String text, Result reply) {
        if (destroyed || !ready || engine == null) {
            reply.error("tts_unavailable", "System speech is not ready"); return;
        }
        stop();
        try {
            boolean chinese = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i); if (c >= '\u3400' && c <= '\u9fff') { chinese = true; break; }
            }
            Locale language = chinese ? Locale.SIMPLIFIED_CHINESE : context.getResources().getConfiguration().getLocales().get(0);
            int supported = engine.setLanguage(language);
            if (supported < TextToSpeech.LANG_AVAILABLE) {
                reply.error("tts_language_unavailable", "The system speech engine has no voice for " + language);
                event("on_error");
                Toast.makeText(context, "系统语音引擎缺少所需语音，请在文字转语音设置中安装语音数据。", Toast.LENGTH_LONG).show();
                return;
            }
            final int request = ++focusGeneration;
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                    if (change < 0 && request == focusGeneration) stop();
                }, main).build();
            if (audio == null || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                releaseFocus(); reply.error("tts_audio_busy", "Audio focus is unavailable"); event("on_error");
                Toast.makeText(context, "暂时无法播放天气播报，请在通话或其他音频结束后重试。", Toast.LENGTH_SHORT).show();
                return;
            }
            String id = "weather-" + (++serial);
            utterance = id;
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                ready = false; ended(id, true); releaseEngine();
                reply.error("tts_speak_failed", "System speech rejected the narration"); return;
            }
            startTimeout = () -> { if (current(id)) {
                try { engine.stop(); } catch (RuntimeException ignored) {}
                ended(id, true);
            } };
            main.postDelayed(startTimeout, 15000);
            reply.success(null);
            Log.i(TAG, "Narration queued, characters=" + text.length());
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot speak weather", error);
            stop(); ready = false; releaseEngine(); event("on_error");
            reply.error("tts_speak_failed", "System speech failed");
        }
    }

    private boolean current(String id) { return !destroyed && id != null && id.equals(utterance); }
    private void ended(String id, boolean failed) {
        if (!current(id)) return;
        utterance = null; cancelStartTimeout(); releaseFocus();
        event(failed ? "on_error" : "on_done");
        if (failed) Toast.makeText(context, "天气播报失败，请检查系统语音引擎或网络后重试。", Toast.LENGTH_SHORT).show();
    }
    private void cancelStartTimeout() {
        if (startTimeout != null) { main.removeCallbacks(startTimeout); startTimeout = null; }
    }
    private void releaseFocus() {
        ++focusGeneration;
        if (focus != null && audio != null) {
            try { audio.abandonAudioFocusRequest(focus); } catch (RuntimeException error) { Log.w(TAG, "Cannot release audio focus", error); }
        }
        focus = null;
    }
    private void releaseEngine() {
        TextToSpeech old = engine; engine = null;
        if (old != null) try { old.shutdown(); } catch (RuntimeException error) { Log.w(TAG, "Cannot release speech engine", error); }
    }
    public void stop() {
        String old = utterance; utterance = null; cancelStartTimeout();
        if (engine != null) try { engine.stop(); } catch (RuntimeException error) { Log.w(TAG, "Cannot stop speech", error); }
        releaseFocus();
        if (old != null) event("on_done");
    }
    private void event(String method) {
        if (destroyed) return;
        try {
            byte[] message;
            if (jsonEvents) message = new JSONObject().put("method", method).put("args", JSONObject.NULL)
                .toString().getBytes(StandardCharsets.UTF_8);
            else {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Codec.write(bytes, method); Codec.write(bytes, null); message = bytes.toByteArray();
            }
            sender.send(EVENTS, message);
            Log.i(TAG, method);
        } catch (Exception error) { Log.w(TAG, "Cannot send speech state", error); }
    }
    private void cancelInitialization() {
        if (initializing) {
            ++generation; initializing = false; ready = false;
            if (initTimeout != null) { main.removeCallbacks(initTimeout); initTimeout = null; }
            releaseEngine();
            for (Result result : drain()) result.error("tts_cancelled", "Weather narration was cancelled");
        }
    }
    public void onResume() { if (!destroyed) foreground = true; }
    public void onStop() {
        foreground = false;
        stop(); cancelInitialization(); ready = false; releaseEngine();
    }
    public void onDestroy() {
        onStop(); destroyed = true; ++generation; ready = false; releaseEngine();
    }
}
