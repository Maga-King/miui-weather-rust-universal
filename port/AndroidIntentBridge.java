package dev.weather.oneplus;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.SparseArray;
import android.widget.Toast;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import static dev.weather.oneplus.AndroidPlatformServices.*;

/** Typed Android navigation for the original Dart Intent/PackageManager APIs. */
public final class AndroidIntentBridge {
    public static final Object UNHANDLED = new Object();
    private static final String INTENT = "com.android.content.intent.method.channel";
    private static final String PACKAGES = "com.android.package.manager.method.channel";
    private static final String ORIGINAL = "com.miui.weather2";
    private static final String[] EXTRA_TYPES = {"int_extra_map", "double_extra_map", "bool_extra_map",
        "string_extra_map", "int_array_extra_map", "double_array_extra_map", "bool_array_extra_map",
        "string_array_extra_map"};
    private final Activity activity;
    private final AndroidConsent.EventSink events;
    private final SparseArray<Integer> pending = new SparseArray<>();
    private int nextRequest = 0x6200;

    public AndroidIntentBridge(Activity activity, AndroidConsent.EventSink events, Bundle saved) {
        this.activity = activity;
        this.events = events;
        if (saved != null) {
            int[] internal = saved.getIntArray("weather_intent_requests");
            int[] original = saved.getIntArray("weather_intent_originals");
            if (internal != null && original != null && internal.length == original.length) {
                for (int i = 0; i < internal.length; i++) pending.put(internal[i], original[i]);
            }
            nextRequest = saved.getInt("weather_intent_next", 0x6200);
        }
    }

    public Object handleCall(String channel, String method, Object arguments) throws Exception {
        if (PACKAGES.equals(channel)) return packageCall(method, arguments);
        if ("plugins.flutter.io/url_launcher".equals(channel) && "launch".equals(method)) {
            String url = requiredString(value(arguments, "url"));
            Intent target = adapt(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            try { activity.startActivity(target); return true; }
            catch (android.content.ActivityNotFoundException | SecurityException unavailable) { return false; }
        }
        if (!INTENT.equals(channel)) return UNHANDLED;
        if ("finishActivity".equals(method)) { activity.finish(); return null; }
        boolean forResult = "startActivityForResult".equals(method);
        if (!forResult && !"startActivity".equals(method)) return UNHANDLED;
        Intent target = adapt(parse(arguments));
        try {
            if (!forResult) activity.startActivity(target);
            else {
                int original = integer(value(arguments, "request_code"));
                if (pending.size() >= 32) throw new IllegalStateException("Too many pending activities");
                while (pending.get(nextRequest) != null) nextRequest = nextRequest == 0x62ff ? 0x6200 : nextRequest + 1;
                int code = nextRequest;
                nextRequest = nextRequest == 0x62ff ? 0x6200 : nextRequest + 1;
                pending.put(code, original);
                // NEW_TASK prevents Android from returning a result to this Activity.
                target.setFlags(target.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
                try { activity.startActivityForResult(target, code); }
                catch (RuntimeException failure) { pending.remove(code); throw failure; }
            }
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, "未找到可以打开此页面的应用", Toast.LENGTH_SHORT).show();
            throw unavailable;
        }
        return null;
    }

    private Object packageCall(String method, Object args) throws Exception {
        PackageManager manager = activity.getPackageManager();
        if ("get_package_info".equals(method)) {
            String name = localPackage(requiredString(value(args, "package_name")));
            Object flags = value(args, "flags");
            PackageInfo info;
            try { info = manager.getPackageInfo(name, flags == null ? 0 : integer(flags)); }
            catch (PackageManager.NameNotFoundException absent) { return null; }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("packageName", info.packageName);
            result.put("versionName", info.versionName);
            result.put("versionCode", info.getLongVersionCode());
            result.put("firstInstallTime", info.firstInstallTime);
            result.put("lastUpdateTime", info.lastUpdateTime);
            result.put("applicationInfo", Collections.singletonMap("flags", info.applicationInfo == null ? 0 : info.applicationInfo.flags));
            return result;
        }
        if ("get_launch_intent".equals(method) || "start_application".equals(method)) {
            Intent launch = manager.getLaunchIntentForPackage(localPackage(requiredString(args)));
            if ("get_launch_intent".equals(method)) {
                // Dart casts to String, jsonDecodes it, then calls Intent.fromJson.
                return launch == null ? null : new JSONObject(intentValues(launch)).toString();
            }
            if (launch == null) return false;
            try { activity.startActivity(launch); return true; }
            catch (android.content.ActivityNotFoundException | SecurityException unavailable) { return false; }
        }
        if ("is_intent_available".equals(method)) {
            Intent target = adapt(parse(args));
            return manager.resolveActivity(target, PackageManager.MATCH_DEFAULT_ONLY) != null;
        }
        return UNHANDLED;
    }

    private String localPackage(String name) { return ORIGINAL.equals(name) ? activity.getPackageName() : name; }

    static Intent parse(Object source) throws Exception {
        if (source instanceof String) source = new JSONObject((String) source);
        if (!(source instanceof Map) && !(source instanceof JSONObject))
            throw new IllegalArgumentException("Expected an Intent object");
        Intent intent = new Intent();
        Object action = value(source, "action"), data = value(source, "data"), flags = value(source, "flag");
        if (action != null) intent.setAction(requiredString(action));
        if (data != null) intent.setData(Uri.parse(requiredString(data)));
        if (flags != null) intent.setFlags(integer(flags));
        Object categories = value(source, "categories");
        if (categories != null) for (Object category : list(categories)) intent.addCategory(requiredString(category));
        Object component = value(source, "component");
        Object pkg = value(component == null ? source : component, "packageName");
        Object cls = value(component == null ? source : component, "className");
        if (pkg != null && !String.valueOf(pkg).isEmpty()) {
            if (cls != null && !String.valueOf(cls).isEmpty()) {
                String name = requiredString(cls), packageName = requiredString(pkg);
                if (name.startsWith(".")) name = packageName + name;
                intent.setComponent(new ComponentName(packageName, name));
            } else intent.setPackage(requiredString(pkg));
        }
        Object extras = value(source, "extras");
        for (String type : EXTRA_TYPES) {
            for (Map.Entry<String, Object> entry : map(value(extras, type)).entrySet())
                putExtra(intent, type, entry.getKey(), entry.getValue());
        }
        return intent;
    }

    private Intent adapt(Intent intent) {
        ComponentName component = intent.getComponent();
        // Dart hardcodes a Xiaomi/AOSP settings component; use the public action.
        if (component != null && "com.android.settings.Settings$LocationSettingsActivity".equals(component.getClassName()))
            return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        String action = intent.getAction();
        if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action))
            return new Intent(action, Uri.fromParts("package", activity.getPackageName(), null));
        if (Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action))
            return new Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        String url = AndroidWeatherPages.webUrl(intent.getDataString());
        if (url == null) url = AndroidWeatherPages.webUrl(intent.getStringExtra("browser_fallback_url"));
        if (url == null && AndroidWeatherPages.isWeatherVideo(intent.getDataString())) url = AndroidWeatherPages.WEATHER_VIDEO_FALLBACK;
        if (Intent.ACTION_VIEW.equals(action) && url != null)
            return new Intent(activity, WeatherWebActivity.class).putExtra(WeatherWebActivity.EXTRA_URL, url);
        Uri uri = intent.getData();
        if (Intent.ACTION_VIEW.equals(action) && uri != null && "weather".equals(uri.getScheme())
                && "weather2.miui.com".equals(uri.getHost()))
            return intent.setPackage(null).setComponent(activity.getComponentName());
        // Do not resolve missing Xiaomi activities to an unrelated host screen.
        if (component == null && ORIGINAL.equals(intent.getPackage())) intent.setPackage(activity.getPackageName());
        return intent;
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        Integer original = pending.get(requestCode);
        if (original == null) return false;
        pending.remove(requestCode);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("request_code", original); event.put("result_code", resultCode);
        event.put("result_data", data == null ? Collections.emptyMap() : intentValues(data));
        events.invokeMethod(INTENT, "onActivityResult", event);
        return true;
    }

    public void saveState(Bundle state) {
        int[] internal = new int[pending.size()], original = new int[pending.size()];
        for (int i = 0; i < internal.length; i++) { internal[i] = pending.keyAt(i); original[i] = pending.valueAt(i); }
        state.putIntArray("weather_intent_requests", internal); state.putIntArray("weather_intent_originals", original);
        state.putInt("weather_intent_next", nextRequest);
    }
}
