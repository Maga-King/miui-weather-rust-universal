package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** The shared_preferences_android legacy Pigeon API, carried by BasicMessageChannel. */
final class AndroidPigeonPreferences {
    private static final String CHANNEL = "dev.flutter.pigeon.shared_preferences_android.SharedPreferencesApi.";
    private static final String LIST = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu";
    private static final String BIG_INTEGER = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy";
    private static final String DOUBLE = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu";
    private final SharedPreferences preferences;

    AndroidPigeonPreferences(Context context) {
        this(context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE));
    }

    AndroidPigeonPreferences(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    /** Returns null for unrelated channels. Handled replies already contain the complete wire message. */
    byte[] handleMessage(String channel, byte[] data) {
        if (!channel.startsWith(CHANNEL)) return null;
        String method = channel.substring(CHANNEL.length());
        // Pigeon allows an optional messenger suffix after the method name.
        int suffix = method.indexOf('.');
        if (suffix >= 0) method = method.substring(0, suffix);
        if (!Arrays.asList("getAll", "clear", "remove", "setBool", "setString", "setInt",
                "setDouble", "setEncodedStringList", "setStringList", "setDeprecatedStringList").contains(method)) return null;
        try {
            ByteBuffer buffer = Codec.buffer(data);
            Object decoded = Codec.read(buffer);
            if (buffer.hasRemaining() || !(decoded instanceof List))
                throw new IllegalArgumentException("Expected a single Pigeon argument list");
            List<?> args = (List<?>) decoded;
            int arity = method.equals("remove") ? 1 : 2;
            if (args.size() != arity) throw new IllegalArgumentException("Wrong argument count for " + method);
            String key = require(args.get(0), String.class);
            Object result;
            if (method.equals("getAll") || method.equals("clear")) {
                Set<String> allowList = args.get(1) == null ? null : new HashSet<>(stringList(args.get(1)));
                Map<String, Object> values = new LinkedHashMap<>();
                SharedPreferences.Editor editor = method.equals("clear") ? preferences.edit() : null;
                for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                    String name = entry.getKey();
                    if (!name.startsWith(key) || (allowList != null && !allowList.contains(name))) continue;
                    if (editor != null) editor.remove(name);
                    else values.put(name, restore(entry.getValue()));
                }
                result = editor == null ? values : editor.commit();
            } else {
                SharedPreferences.Editor editor = preferences.edit();
                Object value = args.size() == 2 ? args.get(1) : null;
                switch (method) {
                    case "remove": editor.remove(key); break;
                    case "setBool": editor.putBoolean(key, require(value, Boolean.class)); break;
                    case "setInt":
                        if (!(value instanceof Integer) && !(value instanceof Long))
                            throw new IllegalArgumentException("Expected an integer");
                        editor.putLong(key, ((Number) value).longValue()); break;
                    case "setDouble":
                        editor.putString(key, DOUBLE + require(value, Double.class)); break;
                    case "setString": {
                        String text = require(value, String.class);
                        if (text.startsWith(LIST) || text.startsWith(BIG_INTEGER) || text.startsWith(DOUBLE))
                            throw new IllegalArgumentException("String conflicts with a shared-preferences type prefix");
                        editor.putString(key, text); break;
                    }
                    case "setEncodedStringList":
                        // This API receives the complete JSON-list prefix and payload from Dart.
                        editor.putString(key, require(value, String.class)); break;
                    case "setStringList":
                    case "setDeprecatedStringList": {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (ObjectOutputStream stream = new ObjectOutputStream(bytes)) {
                            stream.writeObject(stringList(value));
                        }
                        editor.putString(key, LIST + Base64.getEncoder().encodeToString(bytes.toByteArray())); break;
                    }
                    default: throw new IllegalStateException(method);
                }
                result = editor.commit();
            }
            // Pigeon success is [result] with StandardMessageCodec, with no method-envelope byte.
            return Codec.encode(Collections.singletonList(result), false);
        } catch (Exception error) {
            StringWriter details = new StringWriter();
            error.printStackTrace(new PrintWriter(details));
            return Codec.encode(Arrays.asList(error.getClass().getSimpleName(), error.getMessage(), details.toString()), false);
        }
    }

    private static Object restore(Object value) throws Exception {
        if (value instanceof Set) return stringList(new ArrayList<>((Set<?>) value));
        if (!(value instanceof String)) return value;
        String text = (String) value;
        if (text.startsWith(LIST + "!")) return text; // Dart decodes the newer JSON representation.
        if (text.startsWith(LIST)) {
            byte[] bytes = Base64.getMimeDecoder().decode(text.substring(LIST.length()));
            try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
                @Override protected Class<?> resolveClass(ObjectStreamClass descriptor)
                        throws IOException, ClassNotFoundException {
                    if (!descriptor.getName().equals("java.util.ArrayList"))
                        throw new InvalidClassException("Unexpected stored list class", descriptor.getName());
                    return super.resolveClass(descriptor);
                }
            }) {
                return stringList(stream.readObject());
            }
        }
        if (text.startsWith(DOUBLE)) return Double.valueOf(text.substring(DOUBLE.length()));
        if (text.startsWith(BIG_INTEGER)) {
            // This app starts with a fresh sandbox; refuse unsupported legacy BigInteger values
            // rather than allowing Codec's general Number handling to lose integer precision.
            throw new IllegalArgumentException("Unsupported legacy BigInteger preference");
        }
        return text;
    }

    private static ArrayList<String> stringList(Object value) {
        List<?> source = require(value, List.class);
        ArrayList<String> result = new ArrayList<>();
        for (Object item : source) result.add(require(item, String.class));
        return result;
    }

    private static <T> T require(Object value, Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("Expected " + type.getSimpleName());
        return type.cast(value);
    }
}
