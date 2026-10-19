package dev.weather.oneplus;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordinary Android implementations of platform channels used by the original Dart UI. */
public final class AndroidPlatformServices {
    public static final Object UNHANDLED = new Object();
    private static final String TAG = "WeatherPlatform";
    private static final String BROADCAST = "de.kevlatus.flutter_broadcasts";
    private static final String SHORTCUT = "com.android.content.shortcut.method.channel";
    private static final String SYMBOL = "com.xiaomi.hyperos/symbol_font";
    private static final String INTENT = "com.android.content.intent.method.channel";
    private static final String SYSTEM = "com.miui.hyper.system_bridge";
    private static final String FONT_SCALE = "key_miui_font_weight_scale";
    private static final String[] EXTRA_TYPES = {"int_extra_map", "double_extra_map", "bool_extra_map",
            "string_extra_map", "int_array_extra_map", "double_array_extra_map", "bool_array_extra_map",
            "string_array_extra_map"};

    public interface MessageSink {
        /** Send bytes unchanged; broadcast and system-bridge events use StandardMethodCodec. */
        void send(String channel, byte[] message);
    }

    private final Activity activity;
    private final Context context;
    private final MessageSink events;
    private final Map<Integer, BroadcastReceiver> receivers = new LinkedHashMap<>();
    private boolean destroyed;
    private Intent dispatchedIntent;

    public AndroidPlatformServices(Activity activity, MessageSink events) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.events = events;
    }

    /** Call on the main thread. UNHANDLED leaves unknown methods with the host. */
    public Object handleCall(String channel, String method, Object arguments) throws JSONException {
        if (destroyed) return UNHANDLED;
        if (BROADCAST.equals(channel)) {
            if ("startReceiver".equals(method)) return startReceiver(arguments);
            if ("stopReceiver".equals(method)) return stopReceiver(arguments);
            return UNHANDLED;
        }
        if (SYMBOL.equals(channel) && "getFontWeightInfo".equals(method)) {
            // The shipped Dart defaults to scale 50 (symbol weight 330). No vendor setting
            // is created or written when running on Android without that preference.
            int scale = Settings.System.getInt(context.getContentResolver(), FONT_SCALE, -1);
            if (scale < 0) scale = Settings.Global.getInt(context.getContentResolver(), FONT_SCALE, 50);
            return Collections.singletonMap("hyperosScale", Math.max(0, Math.min(100, scale)));
        }
        if (INTENT.equals(channel) && "startActivity".equals(method)
                && "dev.weather.oneplus.DISPLAY_CONFIG".equals(value(arguments, "action"))) {
            activity.startActivity(new Intent(activity, WeatherAppearanceActivity.class));
            return null;
        }
        if (INTENT.equals(channel) && "startActivity".equals(method)
                && "dev.weather.oneplus.MANAGE_WIDGETS".equals(value(arguments, "action"))) {
            activity.startActivity(new Intent(activity, WeatherWidgetManagementActivity.class));
            return null;
        }
        if (INTENT.equals(channel) && "overridePendingTransition".equals(method)) {
            List<?> animations = list(arguments);
            if (animations.size() != 2) throw new IllegalArgumentException("Expected two animation resources");
            // Original package resource IDs cannot address this separately linked APK.
            // Preserve zero/disabled transitions; use public Android fades otherwise.
            int enter = integer(animations.get(0));
            int leave = integer(animations.get(1));
            activity.overridePendingTransition(enter == 0 ? 0 : android.R.anim.fade_in,
                    leave == 0 ? 0 : android.R.anim.fade_out);
            return null;
        }
        if (SHORTCUT.equals(channel)) return shortcuts(method, arguments);
        return UNHANDLED;
    }

    private Object startReceiver(Object arguments) {
        try {
            final int id = integer(value(arguments, "id"));
            if (receivers.containsKey(id)) return "Broadcast receiver is already registered";
            if (receivers.size() >= 32) return "Too many broadcast receivers";
            List<?> names = list(value(arguments, "names"));
            if (names.isEmpty() || names.size() > 64) return "Invalid broadcast action list";
            IntentFilter filter = new IntentFilter();
            for (Object name : names) filter.addAction(requiredString(name));
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (destroyed || receivers.get(id) != this || intent == null) return;
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("receiverId", id);
                    event.put("name", intent.getAction());
                    event.put("data", bundleValues(intent.getExtras()));
                    event.put("timestamp", System.currentTimeMillis());
                    sendStandard(BROADCAST, "receiveBroadcast", event);
                }
            };
            // Register before acknowledging success. Framework system broadcasts are
            // delivered without exposing this receiver to arbitrary application UIDs.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter);
            receivers.put(id, receiver);
            return null; // Dart invokes <String>: null=success, any string=error.
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot register broadcast receiver: " + error.getClass().getSimpleName());
            return "Cannot register broadcast receiver: " + error.getClass().getSimpleName();
        }
    }

    private Object stopReceiver(Object arguments) {
        try {
            int id = integer(value(arguments, "id"));
            BroadcastReceiver receiver = receivers.get(id);
            if (receiver == null) return "Broadcast receiver is not registered";
            context.unregisterReceiver(receiver);
            receivers.remove(id);
            return null;
        } catch (RuntimeException error) {
            return "Cannot unregister broadcast receiver: " + error.getClass().getSimpleName();
        }
    }

    private Object shortcuts(String method, Object arguments) throws JSONException {
        if (!"getDynamicShortcuts".equals(method) && !"addDynamicShortcuts".equals(method)
                && !"updateShortcuts".equals(method) && !"reportShortcutUsed".equals(method)
                && !"removeAllDynamicShortcuts".equals(method)) return UNHANDLED;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) throw new IllegalStateException("ShortcutManager is unavailable");
        if ("getDynamicShortcuts".equals(method)) {
            JSONArray result = new JSONArray();
            for (ShortcutInfo info : manager.getDynamicShortcuts()) {
                JSONObject item = new JSONObject();
                item.put("id", info.getId());
                item.put("rank", info.getRank());
                item.put("title", info.getShortLabel() == null ? "" : info.getShortLabel().toString());
                JSONArray intents = new JSONArray();
                Intent[] stack = info.getIntents();
                if (stack != null) for (Intent intent : stack) intents.put(new JSONObject(intentValues(intent)));
                item.put("intents", intents);
                result.put(item);
            }
            return result.toString(); // Dart casts to String, then jsonDecode -> List.
        }
        if ("reportShortcutUsed".equals(method)) {
            manager.reportShortcutUsed(requiredString(arguments));
            return true; // Only after the real framework call returns.
        }
        if ("removeAllDynamicShortcuts".equals(method)) {
            manager.removeAllDynamicShortcuts();
            return true;
        }
        List<?> raw = list(arguments instanceof String ? new JSONArray((String) arguments) : arguments);
        if (raw.isEmpty() || raw.size() > manager.getMaxShortcutCountPerActivity()) return false;
        List<ShortcutInfo> items = new ArrayList<>();
        for (Object item : raw) items.add(shortcut(item));
        return "addDynamicShortcuts".equals(method) ? manager.addDynamicShortcuts(items) : manager.updateShortcuts(items);
    }

    private ShortcutInfo shortcut(Object item) throws JSONException {
        String id = requiredString(value(item, "id"));
        String title = requiredString(value(item, "title"));
        int rank = integer(value(item, "rank"));
        List<?> stack = list(value(item, "intents"));
        if (stack.isEmpty() || stack.size() > 8) throw new IllegalArgumentException("Invalid shortcut intent stack");
        Intent[] intents = new Intent[stack.size()];
        for (int i = 0; i < intents.length; i++) intents[i] = shortcutIntent(stack.get(i));
        // These resources belong to Android, rather than stale com.miui.weather2 IDs.
        int icon = "id2_24_hour_weather_forecast".equals(id)
                ? android.R.drawable.ic_menu_day : android.R.drawable.ic_menu_recent_history;
        return new ShortcutInfo.Builder(context, id).setShortLabel(title).setRank(Math.max(0, rank))
                .setActivity(activity.getComponentName()).setIntents(intents)
                .setIcon(Icon.createWithResource(context, icon)).build();
    }

    private Intent shortcutIntent(Object source) throws JSONException {
        String action = requiredString(value(source, "action"));
        String data = requiredString(value(source, "data"));
        Uri uri = Uri.parse(data);
        if (!Intent.ACTION_VIEW.equals(action) || !isWeatherUri(uri))
            throw new IllegalArgumentException("Unsupported weather shortcut destination");
        Intent intent = new Intent(action, uri).setComponent(activity.getComponentName());
        Object flag = value(source, "flag");
        if (flag != null) intent.setFlags(integer(flag));
        Object categories = value(source, "categories");
        if (categories != null) for (Object category : list(categories)) intent.addCategory(requiredString(category));
        Object extras = value(source, "extras");
        for (String type : EXTRA_TYPES) {
            Map<String, Object> group = map(value(extras, type));
            for (Map.Entry<String, Object> entry : group.entrySet()) putExtra(intent, type, entry.getKey(), entry.getValue());
        }
        return intent;
    }

    /**
     * Call once the original home/router has initialized, and from onNewIntent when
     * Dart is ready. The original router owns all city/forecast navigation decisions.
     * The same Intent object is delivered only once. Returns false for non-weather intents.
     */
    public boolean dispatchWeatherIntent(Intent intent) {
        if (destroyed || intent == null || intent == dispatchedIntent
                || !Intent.ACTION_VIEW.equals(intent.getAction()) || !isWeatherUri(intent.getData())) return false;
        sendStandard(SYSTEM, "on_new_intent", intentValues(intent));
        dispatchedIntent = intent;
        return true;
    }

    private static boolean isWeatherUri(Uri uri) {
        return uri != null && "weather".equals(uri.getScheme()) && "weather2.miui.com".equals(uri.getHost());
    }

    private void sendStandard(String channel, String method, Object arguments) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Codec.write(bytes, method);
        Codec.write(bytes, arguments);
        events.send(channel, bytes.toByteArray());
    }

    public void onDestroy() {
        destroyed = true;
        for (BroadcastReceiver receiver : receivers.values()) {
            try { context.unregisterReceiver(receiver); }
            catch (IllegalArgumentException error) { Log.w(TAG, "Receiver was already unregistered"); }
        }
        receivers.clear();
    }

    private static Map<String, Object> intentValues(Intent intent) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", intent.getAction());
        values.put("packageName", intent.getPackage());
        values.put("categories", intent.getCategories() == null ? Collections.emptyList() : new ArrayList<>(intent.getCategories()));
        values.put("data", intent.getDataString());
        values.put("flag", intent.getFlags());
        // Inbound Intent.fromJson accepts a flat Bundle; Dart putExtra performs its
        // own type grouping. Outbound Dart intents use the grouped form instead.
        values.put("extras", bundleValues(intent.getExtras()));
        return values;
    }

    private static Map<String, Object> bundleValues(Bundle bundle) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (bundle == null) return result;
        for (String key : bundle.keySet()) {
            Object item = bundle.get(key);
            if (item == null || item instanceof String || item instanceof Boolean || item instanceof Integer
                    || item instanceof Long || item instanceof Double || item instanceof Float || item instanceof byte[]) result.put(key, item);
            else if (item instanceof CharSequence) result.put(key, item.toString());
            else if (item instanceof Bundle) result.put(key, bundleValues((Bundle) item));
            else if (item.getClass().isArray()) {
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < java.lang.reflect.Array.getLength(item); i++) {
                    Object v = java.lang.reflect.Array.get(item, i);
                    if (!(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof String)) { values.clear(); break; }
                    values.add(v);
                }
                result.put(key, values);
            }
        }
        return result;
    }

    private static void putExtra(Intent intent, String type, String key, Object value) throws JSONException {
        if (EXTRA_TYPES[0].equals(type)) intent.putExtra(key, integer(value));
        else if (EXTRA_TYPES[1].equals(type)) intent.putExtra(key, ((Number) value).doubleValue());
        else if (EXTRA_TYPES[2].equals(type)) intent.putExtra(key, (Boolean) value);
        else if (EXTRA_TYPES[3].equals(type)) intent.putExtra(key, (String) value);
        else {
            List<?> values = list(value);
            int n = values.size();
            if (EXTRA_TYPES[4].equals(type)) {
                int[] array = new int[n]; for (int i = 0; i < n; i++) array[i] = integer(values.get(i)); intent.putExtra(key, array);
            } else if (EXTRA_TYPES[5].equals(type)) {
                double[] array = new double[n]; for (int i = 0; i < n; i++) array[i] = ((Number) values.get(i)).doubleValue(); intent.putExtra(key, array);
            } else if (EXTRA_TYPES[6].equals(type)) {
                boolean[] array = new boolean[n]; for (int i = 0; i < n; i++) array[i] = (Boolean) values.get(i); intent.putExtra(key, array);
            } else if (EXTRA_TYPES[7].equals(type)) {
                String[] array = new String[n]; for (int i = 0; i < n; i++) array[i] = (String) values.get(i); intent.putExtra(key, array);
            }
        }
    }

    private static Object value(Object object, String key) {
        Object value = object instanceof JSONObject ? ((JSONObject) object).opt(key)
                : object instanceof Map ? ((Map<?, ?>) object).get(key) : null;
        return value == JSONObject.NULL ? null : value;
    }

    private static String requiredString(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new IllegalArgumentException("Expected nonempty string");
        return (String) value;
    }

    private static int integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer");
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE || ((Number) value).doubleValue() != number)
            throw new IllegalArgumentException("Integer is out of range");
        return (int) number;
    }

    private static List<?> list(Object value) {
        if (value instanceof List) return (List<?>) value;
        if (value instanceof JSONArray) {
            List<Object> result = new ArrayList<>();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) result.add(array.opt(i));
            return result;
        }
        throw new IllegalArgumentException("Expected list");
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, object.opt(key)); }
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(requiredString(entry.getKey()), entry.getValue());
        } else throw new IllegalArgumentException("Expected map");
        return result;
    }
}
