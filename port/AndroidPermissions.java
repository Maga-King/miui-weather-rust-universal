package dev.weather.oneplus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standard Android implementation of the original Weather permission channel. */
public final class AndroidPermissions {
    public static final String CHANNEL = "com.android.permission.method.channel";
    public static final Object UNHANDLED = new Object();
    private static final String TAG = "WeatherPermissions";
    private static final String FINE = Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String COARSE = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION;
    private static final String NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS;
    // Private Android request codes; Dart's request_code is preserved separately.
    private static final int RUNTIME_REQUEST = 0x5741;
    private static final int SETTINGS_REQUEST = 0x5742;

    public interface EventSink {
        /** Send a method call, using the permission channel's method codec. */
        void invokeMethod(String channel, String method, Object arguments);
    }

    private final Activity activity;
    private final EventSink events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Set<String> declared = new HashSet<>();
    private Request current;
    private AlertDialog explanation;
    private boolean resumed;
    private boolean waitingForRuntime;
    private boolean waitingForSettings;
    private boolean settingsActivityPaused;
    private boolean destroyed;

    private static final class Request {
        final int code;
        final List<String> permissions;
        final boolean weatherCallback;
        boolean foregroundAttempted;
        boolean backgroundAttempted;

        Request(int code, List<String> permissions, boolean weatherCallback) {
            this.code = code;
            this.permissions = permissions;
            this.weatherCallback = weatherCallback;
        }
    }

    public AndroidPermissions(Activity activity, EventSink events) {
        this.activity = activity;
        this.events = events;
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                declared.addAll(Arrays.asList(info.requestedPermissions));
            }
        } catch (PackageManager.NameNotFoundException impossible) {
            Log.e(TAG, "Cannot read this application's declared permissions", impossible);
        }
    }

    /**
     * Call before the host's other channel handlers. A request returns an acceptance
     * boolean immediately; the grant results arrive through EventSink asynchronously.
     * Checking permissions never opens a dialog.
     */
    public Object handleCall(String channel, String method, Object arguments) {
        if ("weather_method_channel".equals(channel)
                && ("check_permission".equals(method) || "check_xmscore_permission".equals(method))) {
            // The port replaces XMS's location backend with its own LocationManager.
            // Check this actual backend's Android grant; never assert a grant for another package.
            return checkPermission(permissionArgument(arguments));
        }
        if ("weather_method_channel".equals(channel) && "request_permission".equals(method)) {
            // This older weather-specific API passes a bare list and expects its
            // result event's arguments to be a JSON string, unlike the common API.
            List<String> permissions = permissionList(arguments);
            if (destroyed || permissions == null || permissions.isEmpty()) return false;
            // This legacy API appends a human-readable rationale after the
            // permission names. It must never become an extra denied grant.
            permissions.removeIf(permission -> !permission.startsWith("android.permission."));
            if (permissions.isEmpty()) return false;
            enqueue(new Request(0, permissions, true));
            return true;
        }
        if (!CHANNEL.equals(channel)) return UNHANDLED;
        if ("check_permission".equals(method)) {
            return checkPermission(permissionArgument(arguments));
        }
        if ("should_show_request_permission_rationale".equals(method)) {
            String permission = permissionArgument(arguments);
            return permission != null && declared.contains(permission)
                    && Build.VERSION.SDK_INT >= 23
                    && activity.shouldShowRequestPermissionRationale(permission);
        }
        if ("request_permissions".equals(method)) {
            List<String> permissions = permissionList(value(arguments, "permissions"));
            Object code = value(arguments, "request_code");
            if (destroyed || permissions == null || permissions.isEmpty()
                    || !(code instanceof Number)) {
                Log.w(TAG, "Rejected malformed permission request");
                return false;
            }
            enqueue(new Request(((Number) code).intValue(), permissions, false));
            return true;
        }
        return UNHANDLED;
    }

    private void enqueue(Request request) {
        // Posting also guarantees the method acknowledgement precedes its event.
        main.post(() -> {
            if (destroyed) return;
            queue.add(request);
            next();
        });
    }

    /** Returns Android PERMISSION_GRANTED (0) or PERMISSION_DENIED (-1). */
    public int checkPermission(String permission) {
        if (permission == null || !permission.startsWith("android.permission.")) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (BACKGROUND.equals(permission) && Build.VERSION.SDK_INT < 29) {
            return hasForegroundLocation() ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED;
        }
        if (NOTIFICATIONS.equals(permission) && Build.VERSION.SDK_INT < 33) {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            return manager != null && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled())
                    ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
        return Build.VERSION.SDK_INT >= 23 ? activity.checkSelfPermission(permission)
                : activity.getPackageManager().checkPermission(permission, activity.getPackageName());
    }

    public boolean hasForegroundLocation() {
        return checkPermission(COARSE) == PackageManager.PERMISSION_GRANTED
                || checkPermission(FINE) == PackageManager.PERMISSION_GRANTED;
    }

    private void next() {
        if (destroyed || !resumed || current != null) return;
        current = queue.poll();
        if (current != null) advance();
    }

    private void advance() {
        if (current == null || destroyed || !resumed || waitingForRuntime
                || waitingForSettings || explanation != null) return;
        if (activity.isFinishing() || activity.isDestroyed()) {
            finish();
            return;
        }
        if (!current.foregroundAttempted) {
            current.foregroundAttempted = true;
            Set<String> request = new LinkedHashSet<>();
            for (String permission : current.permissions) {
                if (!BACKGROUND.equals(permission) && shouldRequest(permission)) request.add(permission);
            }
            boolean needsBackground = current.permissions.contains(BACKGROUND)
                    && checkPermission(BACKGROUND) != PackageManager.PERMISSION_GRANTED;
            if (needsBackground && declared.contains(BACKGROUND) && !hasForegroundLocation()) {
                if (shouldRequest(COARSE)) request.add(COARSE);
                if (shouldRequest(FINE)) request.add(FINE);
            }
            // Android 12+ requires both permissions in a precision-choice request.
            if (request.contains(FINE) && declared.contains(COARSE)) request.add(COARSE);
            if (!request.isEmpty() && Build.VERSION.SDK_INT >= 23) {
                try {
                    waitingForRuntime = true;
                    activity.requestPermissions(request.toArray(new String[0]), RUNTIME_REQUEST);
                } catch (RuntimeException error) {
                    Log.w(TAG, "System permission request failed", error);
                    finish();
                }
                return;
            }
        }
        if (!current.backgroundAttempted && current.permissions.contains(BACKGROUND)
                && shouldRequest(BACKGROUND) && hasForegroundLocation()) {
            current.backgroundAttempted = true;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    waitingForRuntime = true;
                    activity.requestPermissions(new String[]{BACKGROUND}, RUNTIME_REQUEST);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Background permission request failed", error);
                    finish();
                }
                return;
            }
        }
        finish();
    }

    private boolean shouldRequest(String permission) {
        if (!declared.contains(permission) || checkPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (NOTIFICATIONS.equals(permission) && Build.VERSION.SDK_INT < 33) return false;
        if (BACKGROUND.equals(permission) && Build.VERSION.SDK_INT < 29) return false;
        try {
            PermissionInfo info = activity.getPackageManager().getPermissionInfo(permission, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException unknown) {
            return false;
        }
    }

    private void showBackgroundExplanation() {
        CharSequence option = activity.getPackageManager().getBackgroundPermissionOptionLabel();
        explanation = new AlertDialog.Builder(activity)
                .setTitle("允许后台定位")
                .setMessage("天气请求在应用关闭后获取位置，以更新当前位置的天气。"
                        + "如需开启，请在系统应用设置的定位权限中选择“" + option
                        + "”。你也可以选择暂不开启。")
                .setPositiveButton("前往系统设置", (dialog, which) -> {
                    explanation = null;
                    waitingForSettings = true;
                    settingsActivityPaused = false;
                    try {
                        activity.startActivityForResult(applicationSettingsIntent(), SETTINGS_REQUEST);
                    } catch (RuntimeException error) {
                        waitingForSettings = false;
                        Log.w(TAG, "Cannot open application settings", error);
                        finish();
                    }
                })
                .setNegativeButton("暂不开启", (dialog, which) -> {
                    explanation = null;
                    finish();
                })
                .setOnCancelListener(dialog -> {
                    explanation = null;
                    finish();
                })
                .create();
        explanation.show();
    }

    /** Forward Activity.onRequestPermissionsResult. Return true when consumed. */
    public boolean onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != RUNTIME_REQUEST) return false;
        // Read grants again, including coarse vs. fine, so dismissal is never success.
        waitingForRuntime = false;
        Request completedStage = current;
        if (completedStage != null) main.post(() -> {
            if (current == completedStage) advance();
        });
        return true;
    }

    /** Forward Activity.onActivityResult. Android's settings resultCode is not a grant. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != SETTINGS_REQUEST) return false;
        if (waitingForSettings) {
            waitingForSettings = false;
            finish();
        }
        return true;
    }

    /** Forward Activity.onPause so an initial onResume cannot complete settings early. */
    public void onPause() {
        resumed = false;
        if (waitingForSettings) settingsActivityPaused = true;
    }

    /** Forward Activity.onResume; supports settings implementations that omit a result. */
    public void onResume() {
        resumed = true;
        if (waitingForSettings && settingsActivityPaused) {
            waitingForSettings = false;
            finish();
        } else if (current != null) {
            advance();
        }
        next();
    }

    /** Forward onDestroy before destroying the native message bridge. */
    public void onDestroy() {
        destroyed = true;
        resumed = false;
        queue.clear();
        current = null;
        waitingForRuntime = false;
        waitingForSettings = false;
        if (explanation != null) {
            explanation.setOnCancelListener(null);
            explanation.dismiss();
            explanation = null;
        }
    }

    private void finish() {
        if (destroyed || current == null) return;
        Request completed = current;
        current = null;
        waitingForRuntime = false;
        waitingForSettings = false;
        List<Integer> grants = new ArrayList<>();
        for (String permission : completed.permissions) grants.add(checkPermission(permission));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("permissions", completed.permissions);
        arguments.put("grant_results", grants);
        arguments.put("request_code", completed.code);
        if (completed.weatherCallback) {
            events.invokeMethod("weather_method_channel", "on_request_permission_result",
                    new JSONObject(arguments).toString());
        } else {
            events.invokeMethod(CHANNEL, "on_request_permission_result", arguments);
        }
        main.post(this::next);
    }

    /** Use only for an explicit user action such as the old MIUI permission editor. */
    public boolean openApplicationSettings() {
        try {
            activity.startActivity(applicationSettingsIntent());
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot open application settings", error);
            return false;
        }
    }

    private Intent applicationSettingsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null));
    }

    private static Object value(Object object, String name) {
        if (object instanceof Map) return ((Map<?, ?>) object).get(name);
        if (object instanceof JSONObject) return ((JSONObject) object).opt(name);
        return null;
    }

    private static String permissionArgument(Object argument) {
        if (argument instanceof String) return (String) argument;
        Object named = value(argument, "permission");
        return named instanceof String ? (String) named : null;
    }

    private static List<String> permissionList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object permission : (List<?>) value) {
                if (!(permission instanceof String)) return null;
                result.add((String) permission);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                Object permission = array.opt(i);
                if (!(permission instanceof String)) return null;
                result.add((String) permission);
            }
        } else return null;
        return result;
    }
}
