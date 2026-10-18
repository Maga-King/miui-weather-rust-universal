package dev.weather.oneplus;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** User-selected high-quality app profile; this does not modify Android device identity. */
public final class AndroidDeviceProfile {
    public static final Object UNHANDLED = new Object();
    private static final String DEVICE_CHANNEL = "com.miui.performance.devicelevelutils.method.channel";
    private AndroidDeviceProfile() {}

    public static Object handleCall(String channel, String method, Object arguments) {
        if (DEVICE_CHANNEL.equals(channel)) {
            if ("getDeviceLevel".equals(method)) {
                // Raw MiuiBooster: 1=low, 2=middle, 3=high. Dart takes their minimum.
                Map<String, Object> levels = new LinkedHashMap<>();
                levels.put("cpu_level", 3); levels.put("gpu_level", 3); levels.put("ram_level", 3);
                return levels;
            }
            if ("getMiuiLiteVersion".equals(method) || "getMiuiMiddleVersion".equals(method)) return 0;
        }
        if ("weather_method_channel".equals(channel)) {
            // This is a different value domain: 1/2/3 force the LOW weather renderer.
            // 5 bypasses that low-device gate; the 3/3/3 values above select high tier 2.
            // 6 also activates a distinct tier-3 material branch, unnecessary for high weather quality.
            if ("get_device_com_level".equals(method)) return 5;
            if ("get_device_com_version".equals(method)) return 1;
        }
        if ("com.android.os.system.properties.method.channel".equals(channel)) {
            Object key = arguments instanceof JSONObject ? ((JSONObject) arguments).opt("key")
                : arguments instanceof Map ? ((Map<?, ?>) arguments).get("key") : null;
            if ("ro.config.low_ram.support_miuilite_plus".equals(key)) {
                if ("getString".equals(method)) return "false";
                if ("getBoolean".equals(method) || "getBool".equals(method)) return false;
            }
        }
        return UNHANDLED;
    }
}
