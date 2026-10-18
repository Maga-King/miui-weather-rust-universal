package dev.weather.oneplus;

import android.content.Context;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Read-only access to original Dart namespace/StandardMessageCodec boolean values. */
public final class AndroidWeatherPreferences {
    private AndroidWeatherPreferences() {}
    public static boolean readBoolean(Context context, String namespace, String key, boolean fallback) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(namespace.getBytes(StandardCharsets.UTF_8));
            String name="prefs_"+Base64.encodeToString(hash,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
            String encoded=context.getSharedPreferences(name,Context.MODE_PRIVATE).getString(key,null);
            if (encoded==null) return fallback;
            byte[] value=Base64.decode(encoded,Base64.NO_WRAP);
            if (value.length==1 && value[0]==1) return true;
            if (value.length==1 && value[0]==2) return false;
        } catch (Exception ignored) { /* Malformed storage must not enable a disabled reminder. */ }
        return fallback;
    }
}
