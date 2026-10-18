package dev.weather.oneplus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.json.JSONArray;
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
            // 6 selects the original tier-3 material branch; weather renderer quality remains 2.
            if ("get_device_com_level".equals(method)) return 6;
            if ("get_device_com_version".equals(method)) return 1;
        }
        if ("com.xiaomi.hyperos/hyper_material".equals(channel)) {
            if ("getGlassCapability".equals(method)) {
                Map<String, Object> capabilities = new LinkedHashMap<>();
                capabilities.put("isSupportMaterial", true);
                capabilities.put("isSupportGlass", true);
                return capabilities;
            }
            // The original Dart protocol expects an integer, not a boolean.
            if ("getBackgroundBlurEnable".equals(method)) return 1;
            // Style is an enum, not a quality slider. Keep the original default style.
            if ("getMaterialStyle".equals(method)) return 0;
        }
        if ("com.android.os.provider.settings.method.channel".equals(channel)) {
            Object key = arguments instanceof List && ((List<?>) arguments).size() > 0
                ? ((List<?>) arguments).get(0)
                : arguments instanceof JSONArray ? ((JSONArray) arguments).opt(0) : null;
            Object namespace = arguments instanceof List && ((List<?>) arguments).size() > 1
                ? ((List<?>) arguments).get(1)
                : arguments instanceof JSONArray ? ((JSONArray) arguments).opt(1) : null;
            // App-local compatibility value; never writes Android's secure settings.
            if ("background_blur_enable".equals(key) && "secure".equals(namespace)) {
                if ("getLong".equals(method)) return 1L;
                if ("getString".equals(method)) return "1";
            }
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
