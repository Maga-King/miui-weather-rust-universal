package dev.weather.oneplus;

import android.content.Context;
import android.os.Build;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** User-selected high-quality app profile; this does not modify Android device identity. */
public final class AndroidDeviceProfile {
    public static final Object UNHANDLED = new Object();
    private static final String DEVICE_CHANNEL = "com.miui.performance.devicelevelutils.method.channel";
    private static final String PROPERTY_CHANNEL = "com.android.os.system.properties.method.channel";
    // App-local flagship identity requested by the user. The original weather
    // code has downgrade lists (including marble), not a flagship whitelist.
    private static final String PROFILE_DEVICE = "xuanyuan";
    private static final String PROFILE_MODEL = "25010PN30C";
    private AndroidDeviceProfile() {}
    private static native void nativeHomeCardBlur(boolean enabled);

    public static void applyHomeCardBlur(Context context) {
        nativeHomeCardBlur(WeatherAppearancePreferences.homeCardBlurEnabled(context));
    }

    static Map<String, Object> systemProperties() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("DEVICE", PROFILE_DEVICE);
        profile.put("MODEL", PROFILE_MODEL);
        profile.put("VERSION.INCREMENTAL", Build.VERSION.INCREMENTAL);
        profile.put("VERSION.RELEASE", Build.VERSION.RELEASE);
        profile.put("SDK_INT", Build.VERSION.SDK_INT);
        profile.put("HARDWARE", "qcom");
        profile.put("CHARACTERISTICS", "nosdcard");
        profile.put("MULTI_DISPLAY_TYPE", 0);
        profile.put("MULTI_DISPLAY_TYPE_OLD", 0);
        // Our bundled Majestic renderer implements this in the app. It is a
        // capability, independent of the user's home material preference.
        profile.put("SUPPORTS_BACKGROUND_BLUR", true);
        profile.put("IS_MIUI", true);
        profile.put("MIUI_VERSION", "V816");
        profile.put("IS_INTERNATIONAL_BUILD", false);
        profile.put("CUSTOMIZED_REGION", "CN");
        profile.put("IS_ALPHA_BUILD", false);
        profile.put("IS_DEVELOPMENT_VERSION", false);
        // A normal app must not synthesize an advertising ID.
        profile.put("OAID", "");
        return profile;
    }

    public static Object handleCall(Context context, String channel, String method, Object arguments) {
        if ("com.xiaomi.hyperos/device_info".equals(channel) && "getDeviceInfo".equals(method)) {
            // HyperUI casts every key and value to String. Renderer capabilities
            // belong to the separate system-properties channel below.
            Map<String, String> device = new LinkedHashMap<>();
            device.put("device", PROFILE_DEVICE); device.put("model", PROFILE_MODEL);
            device.put("manufacturer", "Xiaomi"); device.put("brand", "Xiaomi");
            device.put("product", PROFILE_DEVICE);
            return device;
        }
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
            // General HyperMaterial glass remains enabled. The home-card switch
            // has its own secure-setting query below and must not flatten buttons.
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
                boolean enabled = WeatherAppearancePreferences.homeCardBlurEnabled(context);
                if ("getLong".equals(method)) return enabled ? 1L : 0L;
                if ("getString".equals(method)) return enabled ? "1" : "0";
            }
        }
        if (PROPERTY_CHANNEL.equals(channel)) {
            if ("getAll".equals(method)) return systemProperties();
            Object key = arguments instanceof JSONObject ? ((JSONObject) arguments).opt("key")
                : arguments instanceof Map ? ((Map<?, ?>) arguments).get("key") : null;
            if ("ro.miui.region".equals(key) || "ro.miui.customized.region".equals(key)) {
                if ("getString".equals(method) || "get".equals(method)) return "CN";
            }
            if ("getString".equals(method) || "get".equals(method)) {
                if ("ro.product.mod_device".equals(key)) return PROFILE_DEVICE;
                if ("ro.build.version.incremental".equals(key)) return Build.VERSION.INCREMENTAL;
                if ("ro.build.type".equals(key)) return Build.TYPE;
            }
            if ("ro.config.low_ram.support_miuilite_plus".equals(key)) {
                if ("getString".equals(method)) return "false";
                if ("getBoolean".equals(method) || "getBool".equals(method)) return false;
            }
        }
        return UNHANDLED;
    }
}
