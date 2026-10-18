package dev.weather.oneplus;

import android.content.Context;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** path_provider_android's BasicMessageChannel API used by cached network images. */
public final class AndroidPigeonPathProvider {
    private static final String PREFIX = "dev.flutter.pigeon.path_provider_android.PathProviderApi.";
    private final Context context;

    public AndroidPigeonPathProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns a complete StandardMessageCodec Pigeon reply, or null if unrelated. */
    public byte[] handleMessage(String channel, byte[] message) {
        if (!channel.startsWith(PREFIX)) return null;
        String method = channel.substring(PREFIX.length());
        int suffix = method.indexOf('.');
        if (suffix >= 0) method = method.substring(0, suffix);
        if (!Arrays.asList("getTemporaryPath", "getApplicationSupportPath",
                "getApplicationDocumentsPath", "getExternalStoragePath").contains(method)) return null;
        try {
            // Generated Dart sends null, normally a NULL buffer with zero bytes.
            // Also accept an explicit codec null or empty argument list.
            if (message != null && message.length > 0) {
                ByteBuffer bytes = Codec.buffer(message);
                Object decoded = Codec.read(bytes);
                if (bytes.hasRemaining() || (decoded != null
                        && (!(decoded instanceof List) || !((List<?>) decoded).isEmpty())))
                    throw new IllegalArgumentException("Expected no path-provider arguments");
            }
            File directory;
            if ("getTemporaryPath".equals(method)) directory = context.getCacheDir();
            else if ("getApplicationSupportPath".equals(method)) directory = context.getFilesDir();
            else if ("getApplicationDocumentsPath".equals(method)) directory = context.getDir("flutter", Context.MODE_PRIVATE);
            else directory = context.getExternalFilesDir(null);
            if (directory != null && !directory.isDirectory() && !directory.mkdirs())
                throw new IllegalStateException("Android application directory is unavailable");
            return Codec.encode(Collections.singletonList(directory == null ? null : directory.getAbsolutePath()), false);
        } catch (RuntimeException error) {
            return Codec.encode(Arrays.asList(error.getClass().getSimpleName(), error.getMessage(), null), false);
        }
    }
}
