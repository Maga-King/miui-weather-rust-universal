package dev.weather.oneplus;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

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
    private static final int BACKGROUND_REQUEST_FIRST = 0x5800;
    private static final int BACKGROUND_REQUEST_LAST = 0x6fff;
    private static final long BACKGROUND_WAIT_MS = 3000;
    private static int nextBackgroundRequest = BACKGROUND_REQUEST_FIRST;

    public interface EventSink {
        /** Send a method call, using the permission channel's method codec. */
        void invokeMethod(String channel, String method, Object arguments);
    }
    public interface GrantResult { void complete(boolean granted); }

    private final Activity activity;
    private final EventSink events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Set<String> declared = new HashSet<>();
    private Request current;
    private boolean resumed;
    private boolean waitingForRuntime;
    private boolean backgroundPending;
    private boolean backgroundUiObserved;
    private int backgroundRequestCode;
    private long backgroundDeadline;
    private Runnable backgroundCheck;
    private boolean destroyed;

    private static final class Request {
        final int code;
        final List<String> permissions;
        final boolean weatherCallback;
        final GrantResult completion;
        boolean foregroundAttempted;
        boolean backgroundAttempted;

        Request(int code, List<String> permissions, boolean weatherCallback) {
            this(code, permissions, weatherCallback, null);
        }
        Request(int code, List<String> permissions, boolean weatherCallback, GrantResult completion) {
            this.code = code;
            this.permissions = permissions;
            this.weatherCallback = weatherCallback;
            this.completion = completion;
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
            Log.i(TAG, "Queued permission request code=" + request.code
                    + " legacy=" + request.weatherCallback + " permissions=" + request.permissions);
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

    /** A real foreground location operation can request a missing Android grant.
     * Permission checks remain passive. The same queue serializes Dart requests
     * and this backend fallback, so Android never receives overlapping dialogs.
     */
    public void requestForegroundLocation(GrantResult result) {
        if (destroyed) return;
        if (hasForegroundLocation()) {
            main.post(() -> { if (!destroyed) result.complete(hasForegroundLocation()); });
            return;
        }
        enqueue(new Request(0, Arrays.asList(COARSE, FINE), false, result));
    }

    private void next() {
        if (destroyed || !resumed || current != null) return;
        current = queue.poll();
        if (current != null) advance();
    }

    private void advance() {
        if (current == null || destroyed || !resumed || waitingForRuntime
                || backgroundPending) return;
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
                    Log.i(TAG, "Requesting foreground permissions " + request);
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
                requestBackground();
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

    private static synchronized int allocateBackgroundRequestCode() {
        int result = nextBackgroundRequest++;
        if (nextBackgroundRequest > BACKGROUND_REQUEST_LAST) nextBackgroundRequest = BACKGROUND_REQUEST_FIRST;
        return result;
    }

    private void requestBackground() {
        backgroundRequestCode = allocateBackgroundRequestCode();
        backgroundPending = true;
        backgroundUiObserved = false;
        backgroundDeadline = SystemClock.uptimeMillis() + BACKGROUND_WAIT_MS;
        waitingForRuntime = true;
        try {
            // On Android 11+ PermissionController can open its location permission
            // page itself. NO_UI_SETTINGS_REDIRECT does not mean there is no UI.
            // Keep foreground and background separate on every ROM, including MIUI.
            Log.i(TAG, "Requesting system background location code=" + backgroundRequestCode);
            activity.requestPermissions(new String[]{BACKGROUND}, backgroundRequestCode);
        } catch (RuntimeException error) {
            waitingForRuntime = false;
            Log.w(TAG, "System background permission request failed", error);
        }
        scheduleBackgroundCheck();
    }

    private void scheduleBackgroundCheck() {
        if (backgroundCheck != null) main.removeCallbacks(backgroundCheck);
        backgroundCheck = null;
        if (!backgroundPending || destroyed || !resumed || !activity.hasWindowFocus()) return;
        final Request request = current;
        final int code = backgroundRequestCode;
        backgroundCheck = () -> {
            backgroundCheck = null;
            if (destroyed || current != request || !backgroundPending || backgroundRequestCode != code) return;
            // A permission page/dialog may pause the host or only take its focus.
            // Never time out a user who is still interacting with that system UI.
            if (!resumed || !activity.hasWindowFocus()) return;
            if (activity.isFinishing() || activity.isDestroyed()) { finish(); return; }
            if (checkPermission(BACKGROUND) != PackageManager.PERMISSION_GRANTED && !backgroundUiObserved) {
                Toast.makeText(activity, "如果没出现后台定位授权，请在系统设置开启", Toast.LENGTH_LONG).show();
                Log.i(TAG, "Background permission did not complete in the foreground; continuing without a grant");
            }
            clearBackgroundRequest();
            advance();
        };
        main.postDelayed(backgroundCheck, Math.max(0L, backgroundDeadline - SystemClock.uptimeMillis()));
    }

    private void clearBackgroundRequest() {
        if (backgroundCheck != null) main.removeCallbacks(backgroundCheck);
        backgroundCheck = null;
        backgroundPending = false;
        backgroundRequestCode = 0;
        waitingForRuntime = false;
    }

    /** Forward Activity.onRequestPermissionsResult. Return true when consumed. */
    public boolean onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code >= BACKGROUND_REQUEST_FIRST && code <= BACKGROUND_REQUEST_LAST) {
            // Per-request codes isolate delayed OEM results from subsequent requests.
            if (destroyed || !backgroundPending || code != backgroundRequestCode) return true;
            Log.i(TAG, "Background permission result grants=" + Arrays.toString(results));
            waitingForRuntime = false;
            boolean cancelled = permissions == null || permissions.length == 0 || results == null || results.length == 0;
            if (!cancelled && !backgroundUiObserved
                    && checkPermission(BACKGROUND) != PackageManager.PERMISSION_GRANTED) {
                // Some controllers silently deny/rate-limit. Keep the original
                // short delay before continuing and showing the Settings hint.
                scheduleBackgroundCheck();
                return true;
            }
            clearBackgroundRequest();
            Request completedStage = current;
            if (completedStage != null) main.post(() -> {
                if (current == completedStage) advance();
            });
            return true;
        }
        if (code != RUNTIME_REQUEST) return false;
        if (destroyed || current == null || backgroundPending || !waitingForRuntime) return true;
        Log.i(TAG, "Runtime permission result names=" + Arrays.toString(permissions)
                + " grants=" + Arrays.toString(results) + " resumed=" + resumed);
        // Read grants again, including coarse vs. fine, so dismissal is never success.
        waitingForRuntime = false;
        Request completedStage = current;
        if (completedStage != null) main.post(() -> {
            if (current == completedStage) advance();
        });
        return true;
    }

    /** System permission pages return through onRequestPermissionsResult. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return false;
    }

    /** Pause protects the system permission page from the foreground watchdog. */
    public void onPause() {
        resumed = false;
        if (backgroundPending) backgroundUiObserved = true;
        scheduleBackgroundCheck();
    }

    /** Recover a missing OEM result once the host is back in the foreground. */
    public void onResume() {
        resumed = true;
        if (backgroundPending) {
            if (backgroundUiObserved) backgroundDeadline = SystemClock.uptimeMillis() + BACKGROUND_WAIT_MS;
            scheduleBackgroundCheck();
        } else if (current != null) advance();
        next();
    }

    /** Window focus is independent of onPause on some system dialogs/multiwindow. */
    public void onWindowFocusChanged(boolean focused) {
        if (backgroundPending) {
            if (!focused) backgroundUiObserved = true;
            else if (backgroundUiObserved) backgroundDeadline = SystemClock.uptimeMillis() + BACKGROUND_WAIT_MS;
            scheduleBackgroundCheck();
        }
    }

    /** Forward onDestroy before destroying the native message bridge. */
    public void onDestroy() {
        destroyed = true;
        resumed = false;
        queue.clear();
        current = null;
        clearBackgroundRequest();
    }

    private void finish() {
        if (destroyed || current == null) return;
        Request completed = current;
        current = null;
        clearBackgroundRequest();
        if (completed.completion != null) {
            // Defer until the Activity's onResume finishes before starting the
            // location backend. A permission dialog itself pauses that Activity.
            main.post(() -> {
                if (!destroyed) completed.completion.complete(hasForegroundLocation());
            });
            main.post(this::next);
            return;
        }
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
