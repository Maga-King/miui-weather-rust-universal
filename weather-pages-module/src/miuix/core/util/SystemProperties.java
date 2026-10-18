package miuix.core.util;
/** Read-only property access; Android rejects unavailable hidden APIs into defaults. */
public final class SystemProperties {
    public static final int PROP_NAME_MAX = 31;
    public static final int PROP_VALUE_MAX = 91;
    public static String get(String key) { return get(key, ""); }
    public static String get(String key, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, fallback);
        } catch (Throwable ignored) { return fallback; }
    }
    public static int getInt(String key, int fallback) { try { return Integer.parseInt(get(key, "")); } catch (Exception e) { return fallback; } }
    public static long getLong(String key, long fallback) { try { return Long.parseLong(get(key, "")); } catch (Exception e) { return fallback; } }
    public static boolean getBoolean(String key, boolean fallback) {
        String value = get(key, "");
        return value.isEmpty() ? fallback : "1".equals(value) || "true".equalsIgnoreCase(value) || "y".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }
    public static void set(String key, String value) { throw new UnsupportedOperationException("System property writes unavailable"); }
    public static void set(String key, int value) { set(key, String.valueOf(value)); }
    public static void set(String key, long value) { set(key, String.valueOf(value)); }
    public static void set(String key, boolean value) { set(key, String.valueOf(value)); }
}
