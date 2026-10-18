package dev.weather.oneplus;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Standard Android notification transport for original Dart and background Java sources. */
public final class AndroidWeatherNotifications {
    public static final Object UNHANDLED = new Object();
    public static final String CHANNEL = "com.android.content.notification.method.channel";
    public static final String DEFAULT_CHANNEL = "weather_alert_channel_id";
    public static final String EXTRA_CLICK = "dev.weather.oneplus.NOTIFICATION_CLICK";
    private static final String TAG = "WeatherNotifications";
    private static final String INTENT_CHANNEL = "com.android.content.intent.method.channel";
    private static final String ORIGINAL_PACKAGE = "com.miui.weather2";
    private static final String INFORMATION = "com.miui.weather2.information";
    private final Activity activity;
    private Intent dispatched;

    public enum PostResult { POSTED, PERMISSION_DENIED, QUIET_HOURS, CHANNEL_BLOCKED, FAILED }
    public interface EventSink { void send(String channel, byte[] message); }
    public static final class NotificationException extends IllegalStateException {
        public final String code;
        NotificationException(PostResult result) {
            super("Weather notification was not posted: " + result.name());
            code = "notification_" + result.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Caller supplies real content and owns duplicate suppression; same ID updates in place. */
    public static final class Request {
        public final int id;
        public final String channelId, title, text;
        public final long whenMs;
        private final Intent clickIntent;
        public Request(int id, String channelId, String title, String text, long whenMs, Intent clickIntent) {
            this.id = id;
            this.channelId = required(channelId, "channelId", 200);
            this.title = required(title, "contentTitle", 512);
            this.text = required(text, "contentText", 16384);
            if (whenMs < 0) throw new IllegalArgumentException("Negative notification time");
            this.whenMs = whenMs;
            this.clickIntent = clickIntent == null ? null : new Intent(clickIntent);
        }
    }

    public AndroidWeatherNotifications(Activity activity) { this.activity = activity; }

    /** AOT: channel/notification maps; cancel_notification takes a bare integer. */
    public Object handleCall(String channel, String method, Object arguments) throws JSONException {
        if (CHANNEL.equals(channel)) {
            if ("create_notification_channel".equals(method)) {
                createChannel(activity, channelSpec(arguments));
                return null;
            }
            if ("cancel_notification".equals(method)) {
                cancel(activity, integer(arguments, "notificationId"));
                return null;
            }
            if ("notify".equals(method)) {
                Payload payload = payload(arguments);
                PostResult result = post(activity, payload.request, payload, System.currentTimeMillis());
                if (result != PostResult.POSTED) throw new NotificationException(result);
                return null;
            }
            return UNHANDLED;
        }
        if (INTENT_CHANNEL.equals(channel) && "startActivity".equals(method)) {
            Map<String, Object> intent = map(arguments);
            if (!Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(intent.get("action"))) return UNHANDLED;
            Map<String, Object> extras = flattenExtras(intent.get("extras"));
            Object target = extras.get(Settings.EXTRA_APP_PACKAGE);
            if (target != null && !ORIGINAL_PACKAGE.equals(target) && !activity.getPackageName().equals(target))
                throw new IllegalArgumentException("Unexpected notification settings package");
            activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
            return null;
        }
        return UNHANDLED;
    }

    public static boolean notificationsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled()
            && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED);
    }

    public static boolean quietHoursEnabled(Context context) {
        return AndroidWeatherPreferences.readBoolean(context, INFORMATION, "night_not_disturb_setting", false);
    }
    public static boolean isQuietHours(Context context, long nowMs) {
        return isQuietHours(quietHoursEnabled(context), nowMs, ZoneId.systemDefault());
    }
    static boolean isQuietHours(boolean enabled, long nowMs, ZoneId zone) {
        int hour = Instant.ofEpochMilli(nowMs).atZone(zone).getHour();
        return enabled && (hour >= 23 || hour < 7);
    }

    /** Works with application Context after process death. Never opens permission UI. */
    public static PostResult postLocal(Context context, Request request) {
        if (request == null) return PostResult.FAILED;
        return post(context, request, null, System.currentTimeMillis());
    }
    public static void cancel(Context context, int notificationId) {
        manager(context).cancel(notificationId);
    }

    private static PostResult post(Context context, Request request, Payload original, long now) {
        try {
            if (!notificationsAllowed(context)) return PostResult.PERMISSION_DENIED;
            if (isQuietHours(context, now)) return PostResult.QUIET_HOURS;
            NotificationManager manager = manager(context);
            NotificationChannel channel = manager.getNotificationChannel(request.channelId);
            if (channel == null) {
                ChannelSpec spec = new ChannelSpec(request.channelId, "天气提醒", "", NotificationManager.IMPORTANCE_DEFAULT,
                    null, false, false, false);
                createChannel(context, spec);
                channel = manager.getNotificationChannel(request.channelId);
            }
            if (channel == null) return PostResult.FAILED;
            if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return PostResult.CHANNEL_BLOCKED;
            if (channel.getGroup() != null) {
                NotificationChannelGroup group = manager.getNotificationChannelGroup(channel.getGroup());
                if (group != null && group.isBlocked()) return PostResult.CHANNEL_BLOCKED;
            }
            Intent click = original == null ? localClick(context, request.clickIntent)
                : originalClick(context, original.contentIntent);
            int requestCode = original == null ? request.id : original.requestCode;
            // Distinct notification IDs must not share a PendingIntent and overwrite each other's extras.
            click.addCategory(context.getPackageName() + ".weather_notification." + request.id);
            PendingIntent content = PendingIntent.getActivity(context, requestCode, click,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = new Notification.Builder(context, request.channelId)
                .setContentTitle(request.title).setContentText(request.text)
                .setStyle(new Notification.BigTextStyle().bigText(request.text))
                .setContentIntent(content).setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(original == null || original.autoCancel)
                .setShowWhen(original == null || original.showWhen)
                .setWhen(request.whenMs).setOnlyAlertOnce(true);
            if (original != null && original.icon != null) builder.setSmallIcon(assetIcon(context, original.icon));
            else {
                int icon = context.getResources().getIdentifier("weather_notification_icon", "drawable", context.getPackageName());
                if (icon == 0) throw new IllegalStateException("Missing original weather notification icon");
                builder.setSmallIcon(icon);
            }
            if (original != null) {
                builder.setPriority(original.priority);
                // Android 8+ preserves the user's channel sound/importance settings.
                if (original.sound instanceof String) builder.setSound(soundUri((String) original.sound));
                else if (Boolean.FALSE.equals(original.sound)) {
                    builder.setSound(null).setDefaults(0).setGroup("weather_silent")
                        .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
                }
            }
            manager.notify(request.id, builder.build());
            return PostResult.POSTED;
        } catch (SecurityException denied) {
            Log.w(TAG, "Notification permission denied", denied); return PostResult.PERMISSION_DENIED;
        } catch (Exception error) {
            Log.w(TAG, "Cannot post weather notification: " + error.getClass().getSimpleName());
            return PostResult.FAILED;
        }
    }

    private static NotificationManager manager(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("NotificationManager unavailable");
        return manager;
    }
    private static void createChannel(Context context, ChannelSpec spec) {
        NotificationManager manager = manager(context);
        NotificationChannel channel = manager.getNotificationChannel(spec.id);
        if (channel == null) {
            channel = new NotificationChannel(spec.id, spec.name, spec.importance);
            if (spec.soundSpecified) {
                Uri sound = spec.sound == null || Boolean.FALSE.equals(spec.sound) ? null
                    : Boolean.TRUE.equals(spec.sound) ? Settings.System.DEFAULT_NOTIFICATION_URI : soundUri((String) spec.sound);
                channel.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            }
            channel.enableLights(spec.lights);
            channel.enableVibration(spec.vibration);
        }
        // Updating labels must never re-enable a channel disabled by the user.
        channel.setName(spec.name);
        channel.setDescription(spec.description);
        manager.createNotificationChannel(channel);
        if (manager.getNotificationChannel(spec.id) == null) throw new IllegalStateException("Notification channel creation failed");
    }

    private static Icon assetIcon(Context context, String asset) throws Exception {
        if (!(asset.equals("assets/images/weather_small_icon.webp") || asset.equals("assets/images_dark/weather_small_icon.webp")))
            throw new IllegalArgumentException("Unsupported notification icon");
        try (InputStream input = context.getAssets().open("flutter_assets/" + asset)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new IllegalArgumentException("Invalid original notification icon");
            return Icon.createWithBitmap(bitmap);
        }
    }
    private static Uri soundUri(String value) {
        Uri uri = Uri.parse(value);
        if (!"content".equals(uri.getScheme()) && !"android.resource".equals(uri.getScheme()))
            throw new IllegalArgumentException("Unsupported notification sound URI");
        return uri;
    }

    private static Intent localClick(Context context, Intent supplied) {
        Intent intent = supplied == null ? new Intent(Intent.ACTION_MAIN) : new Intent(supplied);
        if (intent.getPackage() != null && !context.getPackageName().equals(intent.getPackage()))
            throw new IllegalArgumentException("Notification must open this weather APK");
        if (intent.getComponent() != null && !context.getPackageName().equals(intent.getComponent().getPackageName()))
            throw new IllegalArgumentException("Notification component is outside this APK");
        if (intent.getComponent() == null) {
            Intent launcher=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if(launcher==null||launcher.getComponent()==null)throw new IllegalStateException("Missing weather launcher");
            intent.setComponent(launcher.getComponent());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }
    private static Intent originalClick(Context context, Map<String, Object> source) throws JSONException {
        Map<String, Object> payload = new LinkedHashMap<>(source);
        Object packageName = payload.get("packageName");
        if (packageName != null && !ORIGINAL_PACKAGE.equals(packageName) && !context.getPackageName().equals(packageName))
            throw new IllegalArgumentException("Unexpected weather intent package");
        String name = payload.get("className") == null ? ORIGINAL_PACKAGE + ".ActivityWeatherMain"
            : required(payload.get("className"), "className", 200);
        if (!(name.equals(ORIGINAL_PACKAGE + ".ActivityWeatherMain") || name.equals(ORIGINAL_PACKAGE + ".ActivityMinuteRain")
                || name.equals(ORIGINAL_PACKAGE + ".ActivityAqiDetail") || name.equals(ORIGINAL_PACKAGE + ".ActivityAlertDetail")))
            throw new IllegalArgumentException("Unsupported original weather destination");
        payload.put("className", name);
        payload.put("packageName", ORIGINAL_PACKAGE);
        payload.put("extras", flattenExtras(payload.get("extras")));
        Intent launch = localClick(context, null);
        launch.putExtra(EXTRA_CLICK, new JSONObject(payload).toString());
        return launch;
    }

    /** Invoke once the original router is ready and from onNewIntent. Other intents are ignored. */
    public boolean dispatchClick(Intent intent, EventSink events) {
        if (intent == null || intent == dispatched || !intent.hasExtra(EXTRA_CLICK)) return false;
        try {
            Map<String, Object> payload = map(intent.getStringExtra(EXTRA_CLICK));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Codec.write(bytes, "on_new_intent"); Codec.write(bytes, payload);
            events.send("com.miui.hyper.system_bridge", bytes.toByteArray());
            dispatched = intent;
            return true;
        } catch (Exception error) { Log.w(TAG, "Cannot route weather notification click", error); return false; }
    }

    static final class ChannelSpec {
        final String id, name, description; final int importance;
        final Object sound; final boolean soundSpecified, lights, vibration;
        ChannelSpec(String id, String name, String description, int importance, Object sound,
                boolean soundSpecified, boolean lights, boolean vibration) {
            this.id=id; this.name=name; this.description=description; this.importance=importance;
            this.sound=sound; this.soundSpecified=soundSpecified; this.lights=lights; this.vibration=vibration;
        }
    }
    static ChannelSpec channelSpec(Object arguments) throws JSONException {
        Map<String,Object> data=map(arguments);
        int importance=optionalInt(data.get("importance"), NotificationManager.IMPORTANCE_DEFAULT, "importance");
        if (importance < 0 || importance > NotificationManager.IMPORTANCE_MAX) throw new IllegalArgumentException("Invalid importance");
        Object sound = sound(data.get("sound"));
        return new ChannelSpec(required(data.get("id"), "id", 200), required(data.get("name"), "name", 512),
            optionalString(data.get("description"), "", 16384), importance, sound, data.containsKey("sound"),
            optionalBoolean(data.get("lights"), false), optionalBoolean(data.get("vibration"), false));
    }
    static final class Payload {
        Request request; Map<String,Object> contentIntent;
        int requestCode, priority; String icon; Object sound; boolean autoCancel, showWhen;
    }
    static Payload payload(Object arguments) throws JSONException {
        Map<String,Object> data=map(arguments);
        Payload result=new Payload();
        int id=integer(data.get("notificationId"), "notificationId");
        long when=data.get("when") == null ? 0 : longInteger(data.get("when"), "when");
        result.request=new Request(id, required(data.get("channelId"), "channelId", 200),
            required(data.get("contentTitle"), "contentTitle", 512), required(data.get("contentText"), "contentText", 16384), when, null);
        result.icon=optionalString(data.get("smallIcon"), null, 512);
        result.autoCancel=optionalBoolean(data.get("autoCancel"), true);
        result.showWhen=optionalBoolean(data.get("showWhen"), true);
        result.priority=optionalInt(data.get("priority"), Notification.PRIORITY_DEFAULT, "priority");
        if (result.priority < Notification.PRIORITY_MIN || result.priority > Notification.PRIORITY_MAX)
            throw new IllegalArgumentException("Invalid notification priority");
        result.sound=sound(data.get("sound"));
        Map<String,Object> pending=data.get("contentIntent") == null ? new LinkedHashMap<>() : map(data.get("contentIntent"));
        Object action=pending.get("action");
        if (action != null && !"get_activity".equals(action)) throw new IllegalArgumentException("Unsupported PendingIntent action");
        result.requestCode=optionalInt(pending.get("requestCode"), id, "requestCode");
        if (pending.containsKey("flags")) integer(pending.get("flags"), "flags");
        result.contentIntent=pending.get("intent") == null ? new LinkedHashMap<>() : map(pending.get("intent"));
        return result;
    }
    private static Object sound(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String) return value;
        throw new IllegalArgumentException("Invalid notification sound");
    }
    private static boolean optionalBoolean(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean");
        return (Boolean)value;
    }
    private static String required(Object value, String name, int max) {
        if (!(value instanceof String) || ((String)value).trim().isEmpty() || ((String)value).length()>max)
            throw new IllegalArgumentException("Invalid " + name);
        return (String)value;
    }
    private static String optionalString(Object value, String fallback, int max) {
        if (value == null) return fallback;
        if (!(value instanceof String) || ((String)value).length()>max) throw new IllegalArgumentException("Invalid string");
        return (String)value;
    }
    private static long longInteger(Object value, String name) {
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double)
            throw new IllegalArgumentException("Invalid " + name);
        return ((Number)value).longValue();
    }
    private static int integer(Object value, String name) {
        long number=longInteger(value,name);
        if (number<Integer.MIN_VALUE || number>Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid " + name);
        return (int)number;
    }
    private static int optionalInt(Object value, int fallback, String name) { return value==null?fallback:integer(value,name); }

    static Map<String,Object> flattenExtras(Object value) throws JSONException {
        Map<String,Object> extras=value==null?new LinkedHashMap<>():map(value);
        Map<String,Object> flattened=new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry:extras.entrySet()) {
            if (entry.getKey().endsWith("_extra_map")) flattened.putAll(map(entry.getValue()));
            else flattened.put(entry.getKey(),entry.getValue());
        }
        return flattened;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) throws JSONException {
        if (value instanceof String) value=new JSONObject((String)value);
        Object plain=plain(value);
        if (!(plain instanceof Map)) throw new IllegalArgumentException("Expected notification map");
        return (Map<String,Object>)plain;
    }
    private static Object plain(Object value) throws JSONException {
        if (value==JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            Map<String,Object> result=new LinkedHashMap<>();
            java.util.Iterator<String> keys=((JSONObject)value).keys();
            while(keys.hasNext()) { String key=keys.next(); result.put(key,plain(((JSONObject)value).get(key))); }
            return result;
        }
        if (value instanceof Map) {
            Map<String,Object> result=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
                if(!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Expected string key");
                result.put((String)entry.getKey(),plain(entry.getValue()));
            }
            return result;
        }
        if(value instanceof JSONArray) {
            List<Object> result=new ArrayList<>();
            for(int i=0;i<((JSONArray)value).length();i++) result.add(plain(((JSONArray)value).get(i)));
            return result;
        }
        if(value instanceof List) {
            List<Object> result=new ArrayList<>();
            for(Object item:(List<?>)value) result.add(plain(item));
            return result;
        }
        return value;
    }
}
