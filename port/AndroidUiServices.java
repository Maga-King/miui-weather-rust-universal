package dev.weather.oneplus;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.widget.Toast;
import java.util.Collections;
import java.util.Locale;
import static dev.weather.oneplus.AndroidPlatformServices.*;

/** Public Android equivalents for small UI services used by the Dart engine. */
public final class AndroidUiServices {
    public static final Object UNHANDLED = new Object();
    private final Activity activity;
    public AndroidUiServices(Activity activity) { this.activity = activity; }

    public Object handleCall(String channel, String method, Object args) {
        if ("com.miui.hyper.system_bridge".equals(channel) && "showToast".equals(method)) {
            String text = requiredString(value(args, "text"));
            Object duration = value(args, "duration");
            Toast.makeText(activity, text, duration instanceof Number && ((Number)duration).intValue() == 1
                ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return null;
        }
        if ("com.xiaomi.hyperos/haptic".equals(channel)) {
            if ("check_haptic_feedback_support".equals(method)) {
                Vibrator vibrator = activity.getSystemService(Vibrator.class);
                return vibrator != null && vibrator.hasVibrator();
            }
            if ("perform_haptic_feedback".equals(method)) return haptic(requiredString(args));
            if ("perform_haptic_feedback_with_version".equals(method)) {
                Object name = value(args, "feedback_constant_version2");
                if (name == null) name = value(args, "feedback_constant_version1");
                return haptic(requiredString(name));
            }
            return UNHANDLED;
        }
        if (!"flutter/platform".equals(channel)) return UNHANDLED;
        if ("HapticFeedback.vibrate".equals(method)) {
            haptic(args == null ? "LongPress" : String.valueOf(args)); return null;
        }
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if ("Clipboard.setData".equals(method)) {
            Object text = value(args, "text");
            if (!(text instanceof String)) throw new IllegalArgumentException("Expected clipboard text");
            if (clipboard == null) throw new IllegalStateException("Clipboard is unavailable");
            clipboard.setPrimaryClip(ClipData.newPlainText("", (String)text)); return null;
        }
        if ("Clipboard.getData".equals(method)) {
            if (args != null && !"text/plain".equals(args)) return null;
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(activity);
            return text == null ? null : Collections.singletonMap("text", text.toString());
        }
        return UNHANDLED;
    }

    private boolean haptic(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        int feedback;
        if (key.contains("longpress") || key.contains("heavy")) feedback = HapticFeedbackConstants.LONG_PRESS;
        else if (key.contains("clock") || key.contains("tick") || key.contains("picker")
                || key.contains("mesh") || key.contains("selection")) feedback = HapticFeedbackConstants.CLOCK_TICK;
        else if (key.contains("tap") || key.contains("light")) feedback = HapticFeedbackConstants.KEYBOARD_TAP;
        else if (key.contains("button") || key.contains("medium")) feedback = HapticFeedbackConstants.VIRTUAL_KEY;
        else if (key.contains("boundary")) feedback = Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
        else if (key.contains("confirm") || key.contains("switch")) feedback = Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
        else return false;
        // Respect the system's haptic setting; never force vibration or vendor waveforms.
        return activity.getWindow().getDecorView().performHapticFeedback(feedback);
    }
}
