package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.app.AlarmManager;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.xiaomi.mipush.sdk.MiPushClient;
import com.xiaomi.mipush.sdk.MiPushCommandMessage;
import com.xiaomi.mipush.sdk.MiPushMessage;
import com.xiaomi.mipush.sdk.PushMessageReceiver;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Embedded, unmodified MiPush transport. A request is never reported as registration success. */
public final class AndroidMiPushBridge {
    public static final Object UNHANDLED = new Object();
    public static final String CHANNEL = "mipush_sdk";
    public static final String APP_ID = "2882303761517411749";
    private static final String APP_TOKEN = "5871741118749";
    private static final String TAG = "WeatherMiPush", STORE = "weather_mipush_bridge_v1";
    private static final class MainLoop { static final Handler HANDLER = new Handler(Looper.getMainLooper()); }
    private static volatile Sender sender;
    private static volatile String pendingId;
    private static final String[] SERVICES = {
            "com.xiaomi.push.service.XMPushService", "com.xiaomi.push.service.XMJobService",
            "com.xiaomi.mipush.sdk.PushMessageHandler", "com.xiaomi.mipush.sdk.MessageHandleService"};
    private static final String[] ENTRY_POINTS = {
            "com.xiaomi.push.service.receivers.PingReceiver",
            "com.xiaomi.mipush.sdk.NotificationClickedActivity",
            "dev.weather.oneplus.AndroidMiPushBridge$Receiver"};
    private final Context context;
    public interface Sender { void send(String channel, byte[] methodCall); }

    public AndroidMiPushBridge(Context context) { this.context = context.getApplicationContext(); }
    public static void attach(Sender target) { sender = target; }
    public static void detach(Sender target) { if (sender == target) sender = null; }
    private static SharedPreferences state(Context c) { return c.getSharedPreferences(STORE, 0); }

    /** Null means only that the native request was accepted, as required by the original channel. */
    public Object handleCall(String channel, String method, Object arguments) throws IOException {
        if (!CHANNEL.equals(channel) || !"startXmsfService".equals(method)) return UNHANDLED;
        if (!(arguments instanceof Map)) throw new IllegalArgumentException("MiPush arguments must be a map");
        Map<?, ?> args = (Map<?, ?>) arguments;
        String action = String.valueOf(args.get("intent_action"));
        if ("com.xiaomi.mipush.CLEAR_NOTIFICATION".equals(action)) {
            if (WeatherRefresh.networkAllowed(context)) MiPushClient.clearNotification(context);
            else {
                NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (notifications != null) notifications.cancelAll();
            }
            return null;
        }
        if ("com.xiaomi.mipush.UNREGISTER_APP".equals(action)) {
            pendingId = null;
            if (WeatherRefresh.networkAllowed(context)) {
                MiPushClient.unregisterPush(context);
                state(context).edit().putString("status", "unregister_requested").apply();
            } else onNetworkConsentChanged(context, false);
            return null;
        }
        if (!"com.xiaomi.mipush.REGISTER_APP".equals(action))
            throw new IllegalArgumentException("Unsupported MiPush action: " + action);
        if (!WeatherRefresh.networkAllowed(context)) {
            onNetworkConsentChanged(context, false);
            state(context).edit().putString("status", "consent_required").apply();
            throw new IllegalStateException("MiPush requires the actual network consent");
        }
        Object raw = args.get("mipush_payload");
        if (!(raw instanceof byte[])) throw new IllegalArgumentException("Missing original registration payload");
        Registration request = registration((byte[]) raw);
        if (!APP_ID.equals(request.appId) || !APP_TOKEN.equals(request.token))
            throw new IllegalArgumentException("Unexpected MiPush application credentials");
        Object declaredId = args.get("mipush_app_id");
        if (declaredId != null && !APP_ID.equals(String.valueOf(declaredId)))
            throw new IllegalArgumentException("MiPush appId mismatch");
        onNetworkConsentChanged(context, true);
        if (state(context).getBoolean("transport_blocked", false))
            throw new IllegalStateException("MiPush components could not be restored after consent");
        pendingId = request.id;
        // Never send the old package's raw packet to the embedded SDK. It owns its own request ID.
        state(context).edit().putString("status", "registration_requested")
                .putLong("requested_at", System.currentTimeMillis())
                .putString("package", context.getPackageName()).remove("result_code")
                .remove("reason").putBoolean("verified", false).apply();
        try {
            MiPushClient.registerPush(context, APP_ID, APP_TOKEN);
        } catch (RuntimeException error) {
            state(context).edit().putString("status", "sdk_exception")
                    .putString("exception", error.getClass().getName()).apply();
            throw error;
        }
        Log.i(TAG, "Registration requested for actual package " + context.getPackageName());
        return null;
    }

    /**
     * Call after the authoritative network-consent preference is committed, and at Application startup.
     * Withdrawal performs only local operations: no SDK initialization or final network unregister.
     * All ComponentNames explicitly use this app's package, never the system xmsf package.
     */
    public static synchronized void onNetworkConsentChanged(Context context, boolean allowed) {
        Context c = context.getApplicationContext();
        SharedPreferences prefs = state(c);
        boolean grant = allowed && WeatherRefresh.networkAllowed(c);
        boolean blocked = prefs.getBoolean("transport_blocked", false);
        PackageManager pm = c.getPackageManager();
        if (grant) {
            if (!blocked) return;
            boolean restored = true;
            for (String name : SERVICES) restored &= restoreComponent(c, pm, prefs, name);
            for (String name : ENTRY_POINTS) restored &= restoreComponent(c, pm, prefs, name);
            if (restored) {
                SharedPreferences.Editor cleared = prefs.edit().putBoolean("transport_blocked", false)
                        .putString("status", "consent_granted_waiting_dart_registration");
                for (String name : SERVICES) cleared.remove(componentKey(name));
                for (String name : ENTRY_POINTS) cleared.remove(componentKey(name));
                cleared.commit();
            }
            // Original Dart owns the new request. Restoring consent is not registration success.
            return;
        }
        pendingId = null; // Also invalidates any already queued callback to a live Dart engine.
        SharedPreferences.Editor edit = prefs.edit().putBoolean("transport_blocked", true)
                .putBoolean("verified", false).putString("status", "consent_withdrawn_transport_shutdown_requested")
                .remove("requested_accept_time");
        for (String name : SERVICES) rememberComponent(c, pm, prefs, edit, name);
        for (String name : ENTRY_POINTS) rememberComponent(c, pm, prefs, edit, name);
        edit.commit();
        for (String name : ENTRY_POINTS) disableComponent(c, pm, name);
        for (String name : SERVICES) disableComponent(c, pm, name);
        try {
            JobScheduler jobs = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            ComponentName sdkJob = new ComponentName(c.getPackageName(), "com.xiaomi.push.service.XMJobService");
            if (jobs != null) for (JobInfo job : jobs.getAllPendingJobs())
                if (sdkJob.equals(job.getService())) jobs.cancel(job.getId());
        } catch (RuntimeException error) { Log.w(TAG, "Cannot cancel SDK jobs", error); }
        try {
            Intent ping = new Intent("com.xiaomi.push.PING_TIMER").setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_NO_CREATE | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent existing = PendingIntent.getBroadcast(c, 0, ping, flags);
            if (existing != null) {
                AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                if (alarms != null) alarms.cancel(existing);
                existing.cancel();
            }
        } catch (RuntimeException error) { Log.w(TAG, "Cannot cancel SDK ping alarm", error); }
        for (String name : SERVICES) {
            try { c.stopService(new Intent().setComponent(new ComponentName(c.getPackageName(), name))); }
            catch (RuntimeException error) { Log.w(TAG, "Cannot stop own SDK service " + name, error); }
        }
        // stopService alone cannot destroy a still-bound Messenger service. Terminate only our
        // private SDK process after disabling its restart components, never the weather process.
        try {
            ActivityManager processes = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> running = processes == null ? null : processes.getRunningAppProcesses();
            if (running != null) for (ActivityManager.RunningAppProcessInfo process : running) {
                if (process.uid == android.os.Process.myUid() && process.pid != android.os.Process.myPid()
                        && (c.getPackageName() + ":pushservice").equals(process.processName)) {
                    android.os.Process.killProcess(process.pid);
                }
            }
        } catch (RuntimeException error) { Log.w(TAG, "Cannot stop private SDK process", error); }
        // Remote registration may remain; disabled local components cannot process new delivery.
    }
    private static String componentKey(String name) { return "component_before_block_" + name; }
    private static void rememberComponent(Context c, PackageManager pm, SharedPreferences prefs,
            SharedPreferences.Editor edit, String name) {
        if (prefs.contains(componentKey(name))) return;
        try { edit.putInt(componentKey(name), pm.getComponentEnabledSetting(new ComponentName(c.getPackageName(), name))); }
        catch (RuntimeException error) { Log.w(TAG, "Cannot remember SDK component " + name, error); }
    }
    private static void disableComponent(Context c, PackageManager pm, String name) {
        try { pm.setComponentEnabledSetting(new ComponentName(c.getPackageName(), name),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP); }
        catch (RuntimeException error) { Log.w(TAG, "Cannot disable SDK component " + name, error); }
    }
    private static boolean restoreComponent(Context c, PackageManager pm, SharedPreferences prefs, String name) {
        try {
            pm.setComponentEnabledSetting(new ComponentName(c.getPackageName(), name),
                    prefs.getInt(componentKey(name), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), PackageManager.DONT_KILL_APP);
            return true;
        } catch (RuntimeException error) { Log.w(TAG, "Cannot restore SDK component " + name, error); return false; }
    }

    /** Invoke after preference changes. Server accept-time is in addition to local DND enforcement. */
    public static void onEnvironmentChanged(Context context) {
        if (!WeatherRefresh.networkAllowed(context)) { onNetworkConsentChanged(context, false); return; }
        if (!state(context).getBoolean("verified", false)) return;
        boolean quiet = AndroidWeatherNotifications.quietHoursEnabled(context);
        String hours = quiet ? "07:00-23:00" : "00:00-23:59";
        if (hours.equals(state(context).getString("requested_accept_time", ""))) return;
        try {
            MiPushClient.setAcceptTime(context, quiet ? 7 : 0, 0, 23, quiet ? 0 : 59, null);
            state(context).edit().putString("requested_accept_time", hours).apply();
        } catch (RuntimeException error) {
            Log.w(TAG, "Accept-time request failed: " + error.getClass().getSimpleName());
        }
    }

    private static void registered(Context c, MiPushCommandMessage message) {
        if (message == null || !MiPushClient.COMMAND_REGISTER.equals(message.getCommand())) return;
        if (!WeatherRefresh.networkAllowed(c) || state(c).getBoolean("transport_blocked", false)) {
            onNetworkConsentChanged(c, false); return;
        }
        long code = message.getResultCode();
        String reason = message.getReason();
        List<String> arguments = message.getCommandArguments();
        String regId = arguments == null || arguments.isEmpty() ? "" : arguments.get(0);
        SharedPreferences sdk = c.getSharedPreferences("mipush", 0);
        String secret = sdk.getString("regSec", "");
        boolean verified = code == 0 && !empty(regId) && regId.equals(MiPushClient.getRegId(c))
                && regId.equals(sdk.getString("regId", "")) && !empty(secret)
                && APP_ID.equals(sdk.getString("appId", ""))
                && APP_TOKEN.equals(sdk.getString("appToken", "")) && sdk.getBoolean("valid", false);
        state(c).edit().putString("status", code != 0 ? "server_registration_failed" :
                verified ? "sdk_registration_verified" : "sdk_result_missing_credentials")
                .putLong("result_code", code).putString("reason", reason == null ? "" : reason)
                .putLong("callback_at", System.currentTimeMillis()).putBoolean("verified", verified)
                .putInt("reg_id_length", empty(regId) ? 0 : regId.length()).apply();
        // Do not log regSec, appToken or the full regId.
        Log.i(TAG, "Real SDK register callback code=" + code + " verified=" + verified + " reason=" + reason);
        String requestId = pendingId;
        if (requestId != null && WeatherRefresh.networkAllowed(c) && (verified || code != 0)) {
            try {
                byte[] response = registrationResult(requestId, c.getPackageName(), code, reason,
                        verified ? regId : null, verified ? secret : null);
                final Sender target = sender;
                if (target != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("message_type", null);
                    event.put("msg_action", "com.xiaomi.mipush.RECEIVE_MESSAGE");
                    event.put("mipush_payload", response);
                    ByteArrayOutputStream wire = new ByteArrayOutputStream();
                    Codec.write(wire, "onReceiveMsg"); Codec.write(wire, event);
                    byte[] methodCall = wire.toByteArray();
                    MainLoop.HANDLER.post(() -> {
                        if (sender == target && requestId.equals(pendingId) && WeatherRefresh.networkAllowed(c)
                                && !state(c).getBoolean("transport_blocked", false)) target.send(CHANNEL, methodCall);
                    });
                }
            } catch (IOException error) { Log.w(TAG, "Cannot adapt SDK registration result", error); }
        }
        if (verified) onEnvironmentChanged(c);
        // Registration alone never disables real-weather HTTP reminder fallback.
    }
    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    /** SDK invokes this in the app process, including a cold background delivery. */
    public static final class Receiver extends PushMessageReceiver {
        @Override public void onReceiveRegisterResult(Context c, MiPushCommandMessage result) { registered(c, result); }
        @Override public void onCommandResult(Context c, MiPushCommandMessage result) {
            if (result == null) return;
            // Registration has a separate callback; do not adapt it twice.
            if (!MiPushClient.COMMAND_REGISTER.equals(result.getCommand())) {
                state(c).edit().putString("last_command", result.getCommand())
                        .putLong("last_command_code", result.getResultCode())
                        .putString("last_command_reason", result.getReason()).apply();
            }
        }
        @Override public void onReceivePassThroughMessage(Context c, MiPushMessage message) {
            if (message == null || !WeatherRefresh.networkAllowed(c)
                    || state(c).getBoolean("transport_blocked", false)) return;
            state(c).edit().putLong("last_pass_through_at", System.currentTimeMillis()).apply();
            WeatherAlerts.onMiPushContent(c, message.getMessageId(), message.getContent(), false);
        }
        @Override public void onNotificationMessageClicked(Context c, MiPushMessage message) {
            if (message != null) WeatherAlerts.onMiPushContent(c, message.getMessageId(), message.getContent(), true);
        }
        @Override public void onNotificationMessageArrived(Context c, MiPushMessage message) {
            // SDK notification messages are already displayed. Never post them for a second time.
            state(c).edit().putLong("last_sdk_notification_at", System.currentTimeMillis()).apply();
        }
        @Override public void onRequirePermissions(Context c, String[] permissions) {
            state(c).edit().putString("status", "sdk_permission_required")
                    .putString("required_permissions", java.util.Arrays.toString(permissions)).apply();
            Log.w(TAG, "SDK requested permissions; permission UI remains user initiated");
        }
    }

    static final class Registration { String id, appId, token; }
    /** Small bounded Thrift binary reader, used only for the original unencrypted register request. */
    static Registration registration(byte[] bytes) throws IOException {
        Map<Integer, Object> outer = new ThriftReader(bytes).struct(0);
        if (!Integer.valueOf(1).equals(outer.get(1)) || !Boolean.FALSE.equals(outer.get(2))
                || !Boolean.TRUE.equals(outer.get(3)) || !(outer.get(4) instanceof byte[]))
            throw new IOException("Expected unencrypted MiPush registration request");
        Map<Integer, Object> inner = new ThriftReader((byte[]) outer.get(4)).struct(0);
        Registration result = new Registration();
        result.id = string(inner.get(3)); result.appId = string(inner.get(4)); result.token = string(inner.get(7));
        if (empty(result.id) || result.id.length() > 1024) throw new IOException("Missing registration correlation ID");
        return result;
    }
    static String string(Object value) throws IOException {
        if (!(value instanceof byte[])) throw new IOException("Expected Thrift string");
        return new String((byte[]) value, StandardCharsets.UTF_8);
    }
    static final class ThriftReader {
        final ByteBuffer data;
        int fields;
        ThriftReader(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length > 262144) throw new IOException("MiPush payload size");
            data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        }
        int take(int n) throws IOException { if (n < 0 || data.remaining() < n) throw new IOException("Truncated Thrift"); return n; }
        int count() throws IOException { take(4); int n=data.getInt(); if(n<0||n>8192)throw new IOException("Thrift count");return n; }
        Map<Integer, Object> struct(int depth) throws IOException {
            if(depth>12)throw new IOException("Thrift depth");
            Map<Integer,Object> values=new LinkedHashMap<>();
            while(true){take(1);int type=data.get()&255;if(type==0)return values;
                if(++fields>16384)throw new IOException("Thrift fields");take(2);int id=data.getShort()&65535;
                values.put(id,value(type,depth+1));}
        }
        Object value(int type,int depth) throws IOException {
            if(depth>12)throw new IOException("Thrift depth");
            switch(type){
                case 2:take(1);return data.get()!=0;
                case 3:take(1);return data.get();
                case 4:take(8);return data.getDouble();
                case 6:take(2);return data.getShort();
                case 8:take(4);return data.getInt();
                case 10:take(8);return data.getLong();
                case 11:take(4);int size=data.getInt();byte[] text=new byte[take(size)];data.get(text);return text;
                case 12:return struct(depth);
                case 13:take(2);int key=data.get()&255,val=data.get()&255,n=count();
                    for(int i=0;i<n;i++){value(key,depth+1);value(val,depth+1);}return null;
                case 14:case 15:take(1);int item=data.get()&255,len=count();
                    for(int i=0;i<len;i++)value(item,depth+1);return null;
                default:throw new IOException("Unsupported Thrift type "+type);
            }
        }
    }
    static void field(DataOutputStream out,int type,int id)throws IOException{out.writeByte(type);out.writeShort(id);}
    static void text(DataOutputStream out,int id,String value)throws IOException{
        if(value==null)return;field(out,11,id);byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    static void target(DataOutputStream out)throws IOException{
        field(out,12,7);field(out,10,1);out.writeLong(5);text(out,2,"fakeid");out.writeByte(0);
    }
    /** A protocol adaptation of actual SDK credentials/result, never a claimed raw server packet. */
    static byte[] registrationResult(String id,String pkg,long code,String reason,String regId,String secret)throws IOException{
        ByteArrayOutputStream inner=new ByteArrayOutputStream();DataOutputStream result=new DataOutputStream(inner);
        text(result,3,id);text(result,4,APP_ID);field(result,10,6);result.writeLong(code);
        text(result,7,reason);text(result,8,regId);text(result,9,secret);text(result,10,pkg);result.writeByte(0);
        ByteArrayOutputStream outer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(outer);
        field(out,8,1);out.writeInt(1);field(out,2,2);out.writeBoolean(false);field(out,2,3);out.writeBoolean(false);
        field(out,11,4);out.writeInt(inner.size());inner.writeTo(out);text(out,5,APP_ID);text(out,6,pkg);target(out);out.writeByte(0);
        return outer.toByteArray();
    }
}
