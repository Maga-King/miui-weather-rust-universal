package dev.weather.oneplus;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;
import android.util.Base64;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/** Network scheduling and internal freshness metadata; none is shown on the original widget. */
public final class WeatherRefresh {
    public static final String SOURCE_METADATA = "dev.weather.oneplus.WEATHER_REFRESH_SOURCE";
    static final int PERIODIC_JOB = 0x574501, REQUEST_JOB = 0x574502, ORIGINAL_JOB = 100256;
    static final int AUTO_JOB = 0x574510, AUTO_NEXT_JOB = 0x574511;
    static final int MANUAL_JOB = 0x574512, MANUAL_NEXT_JOB = 0x574513;
    static final long INTERVAL_MS = WeatherRefreshPolicy.INTERVAL_MS, BASE_BACKOFF_MS = WeatherRefreshPolicy.BASE_BACKOFF_MS;
    static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final AtomicBoolean MANUAL_PENDING = new AtomicBoolean();
    private static boolean automaticPending;
    private static int activeJob;
    private static int retiringAutomatic, retiringManual;
    // Package scope permits deterministic JVM checks without changing the phone clock.
    static Clock clock = Clock.systemUTC();
    static long now() { return clock.millis(); }
    private static final String EARLIEST = "earliest_ms", MANUAL = "manual", CHARGING = "charging", AUTO_EPOCH = "automatic_epoch";
    private static final int POLICY_VERSION = 3;
    private static final String INTERVAL_MINUTES="interval_minutes", SCHEDULED_INTERVAL="scheduled_interval_minutes";
    // AndroidPreferences: prefs_ + Base64URL(SHA-256(original namespace)), no padding.
    static final String NIGHT_PREFS = "prefs_aIMd59OyVnIazRDy-UXSXCX7E1GrGWrcsbKhCKaBKfo";
    static final String NIGHT_KEY = "night_update_setting";
    private WeatherRefresh() {}

    static SharedPreferences state(Context context) {
        return context.getSharedPreferences("weather_refresh_state", Context.MODE_PRIVATE);
    }
    public static int intervalMinutes(Context context) {
        int selected=state(context).getInt(INTERVAL_MINUTES,WeatherRefreshPolicy.DEFAULT_INTERVAL_MINUTES);
        return WeatherRefreshPolicy.validIntervalMinutes(selected)?selected:WeatherRefreshPolicy.DEFAULT_INTERVAL_MINUTES;
    }
    public static long intervalMs(Context context) {return intervalMinutes(context)*60_000L;}
    public static void setIntervalMinutes(Context context,int minutes) {
        if(!WeatherRefreshPolicy.validIntervalMinutes(minutes))throw new IllegalArgumentException("Unsupported refresh interval");
        SharedPreferences saved=state(context);
        if(!saved.edit().putInt(INTERVAL_MINUTES,minutes).remove(SCHEDULED_INTERVAL).commit())
            throw new IllegalStateException("Could not save refresh interval");
        ensureScheduled(context);
    }
    /** Re-anchor a new cadence once; desktop events must not push it into the future. */
    private static long applyIntervalPolicy(Context context) {
        SharedPreferences saved=state(context);int minutes=intervalMinutes(context);
        if(saved.getInt(SCHEDULED_INTERVAL,0)==minutes)return saved.getLong("next_periodic_ms",now());
        long time=now(),success=saved.getLong("last_success_ms",0),attempt=saved.getLong("last_attempt_ms",0);
        long anchor=Math.max(success>0&&success<=time?success:0,attempt>0&&attempt<=time?attempt:0);
        long next=anchor==0?time:Math.max(time,WeatherRefreshPolicy.after(anchor,intervalMs(context)));
        saved.edit().putInt(SCHEDULED_INTERVAL,minutes).putLong("next_periodic_ms",next).commit();
        return next;
    }
    static boolean backgroundNeeded(Context context) {
        return WeatherWidgetProvider.hasWidgets(context)||WeatherAlerts.needsBackgroundWeather(context);
    }
    public static void setNetworkAllowed(Context context, boolean allowed) {
        boolean before=networkAllowed(context);
        state(context).edit().putBoolean("network_allowed", allowed).commit();
        if (!allowed) cancel(context);
        else if (backgroundNeeded(context)) {
            ensureScheduled(context); requestAutomatic(context);
        }
        WeatherAlerts.onEnvironmentChanged(context);
        if(before!=allowed)AndroidMiPushBridge.onNetworkConsentChanged(context,allowed);
    }
    static boolean networkAllowed(Context context) { return state(context).getBoolean("network_allowed", false); }
    /** Read the original Dart boolean each time; absence or invalid storage defaults off. */
    public static boolean nightUpdateAllowed(Context context) {
        try {
            String encoded = context.getSharedPreferences(NIGHT_PREFS, Context.MODE_PRIVATE).getString(NIGHT_KEY, null);
            if (encoded == null) return false;
            byte[] value = Base64.decode(encoded, Base64.NO_WRAP);
            // StandardMessageCodec true=1, false=2. Do not coerce strings or numbers.
            return value.length == 1 && value[0] == 1;
        } catch (Exception error) {
            Log.w("WeatherRefresh", "Cannot read night-update preference", error);
            return false;
        }
    }

    /**
     * Called only after a real decoded server response, never a weather.db read.
     * last_success_ms records that fetch, not the age or replacement of the displayed
     * observation. The boolean reports successful bookkeeping/cache handling; retaining
     * a newer snapshot is successful and must not trigger a cache-write retry.
     */
    public static synchronized boolean publishNetworkSuccess(Context context, WeatherWidgetData observation) {
        if (!networkAllowed(context) || observation == null) return false;
        WeatherWidgetCache.WriteResult result = WeatherWidgetCache.publish(context, observation);
        SharedPreferences.Editor update = state(context).edit()
            .putLong("last_success_ms", now())
            .putString("success_city_id", observation.cityId)
            .putString("success_unit", observation.unit)
            .putLong("success_observed_at_ms", observation.observedAtMs)
            .putString("success_cache_result", result.name());
        if (result != WeatherWidgetCache.WriteResult.FAILED) {
            update.putLong("next_attempt_ms", 0).putInt("consecutive_failures", 0).putString("last_error", "");
        }
        boolean recorded = update.commit();
        return recorded && result != WeatherWidgetCache.WriteResult.FAILED;
    }
    /** A normal in-app manual refresh entry point. No widget button or exact alarm. */
    public static boolean requestManual(Context context) {
        if (!ready(context)) return false;
        if (IN_FLIGHT.get()) { MANUAL_PENDING.set(true); return true; }
        return scheduleManual(context, retiringManual);
    }
    /** Original Dart scheduler protocol, delegated to Android's ordinary JobScheduler. */
    public static boolean requestFromOriginal(Context context, Object arguments) {
        if (!(arguments instanceof String) || !ready(context)) return false;
        try {
            org.json.JSONObject request = new org.json.JSONObject((String) arguments);
            if (request.getInt("jobId") != ORIGINAL_JOB) return false;
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler == null) return false;
            migrateLegacy(context);
            if (IN_FLIGHT.get()) { automaticPending = true; return true; }
            if (pendingAutomatic(context, 0) != null) return true;
            long earliest = Math.max(WeatherRefreshPolicy.after(now(), request.optLong("latency", 0)),
                    state(context).getLong("next_periodic_ms", now()));
            // An original overrideDeadline must never defeat quiet hours or freshness.
            return scheduleAutomatic(context, earliest, request.optBoolean("requiresCharging", false), 0, false);
        } catch (Exception invalid) { Log.w("WeatherRefresh", "Cannot schedule original weather task", invalid); return false; }
    }
    public static boolean originalJobScheduled(Context context, Object arguments) {
        if (!(arguments instanceof Number) || ((Number) arguments).intValue() != ORIGINAL_JOB) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        return scheduler != null && (pendingAutomatic(context, 0) != null
                || scheduler.getPendingJob(ORIGINAL_JOB) != null || IN_FLIGHT.get());
    }
    public static boolean requestAutomatic(Context context) {
        if (!ready(context)) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return false;
        if (IN_FLIGHT.get()) {
            // Keep a new explicit intent even if the active fetch belongs to an older
            // city/unit; its completion must not silently consume the user's request.
            return true;
        }
        long time = now();
        migrateLegacy(context);
        if (freshUntil(context, time) > time) return true;
        // A blocked local source has no HTTP freshness. Its configured-cadence
        // successor must still survive desktop/widget events without being advanced.
        return scheduleAutomatic(context, Math.max(time, state(context).getLong("next_periodic_ms", time)), false, 0, false);
    }
    private static long freshUntil(Context context, long time) {
        SharedPreferences saved = state(context);
        WeatherWidgetData data = WeatherWidgetCache.read(context);
        long last = saved.getLong("last_success_ms", 0);
        boolean same = data != null && data.cityId.equals(saved.getString("success_city_id", ""))
            && data.unit.equals(saved.getString("success_unit", ""))
            && data.observedAtMs >= saved.getLong("success_observed_at_ms", 0)
            && !WeatherWidgetCache.WriteResult.FAILED.name().equals(saved.getString("success_cache_result", ""));
        // A backwards clock adjustment must not make old success fresh indefinitely.
        return same && last > 0 && last <= time ? WeatherRefreshPolicy.after(last, intervalMs(context)) : time;
    }
    static long automaticTime(Context context, long earliest) {
        long time = now();
        SharedPreferences saved = state(context);
        long backoff = saved.getLong("next_attempt_ms", 0);
        long maximum = WeatherRefreshPolicy.after(time, WeatherRefreshPolicy.MAX_BACKOFF_MS);
        if (backoff > maximum) {
            // Clamp the stored target once, rather than applying a rolling three-hour
            // delay on every start after a backwards clock correction.
            backoff = maximum; saved.edit().putLong("next_attempt_ms", backoff).commit();
        }
        long due = Math.max(earliest, Math.max(freshUntil(context, time), backoff));
        return WeatherRefreshPolicy.nextAutomaticTime(time, due, ZoneId.systemDefault(), nightUpdateAllowed(context));
    }
    static boolean manual(android.app.job.JobParameters parameters) { return parameters.getExtras().getBoolean(MANUAL, false); }
    static boolean automaticCurrent(Context context, android.app.job.JobParameters parameters) {
        return manual(parameters) || parameters.getExtras().getLong(AUTO_EPOCH, 0) == state(context).getLong(AUTO_EPOCH, 0);
    }
    static long earliest(android.app.job.JobParameters parameters) { return parameters.getExtras().getLong(EARLIEST, 0); }
    static long automaticDelay(Context context, long earliest) { return Math.max(0, automaticTime(context, earliest) - now()); }
    private static boolean schedule(Context context, int id, long earliest, boolean manual, boolean charging) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || !ready(context)) return false;
        long due = manual ? earliest : automaticTime(context, earliest);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(MANUAL, manual); extras.putBoolean(CHARGING, charging);
        if (!manual) extras.putLong(AUTO_EPOCH, state(context).getLong(AUTO_EPOCH, 0));
        extras.putLong(EARLIEST, earliest); extras.putLong("due_ms", due); extras.putInt("policy_version", POLICY_VERSION);
        extras.putInt(INTERVAL_MINUTES,intervalMinutes(context));
        boolean queued = scheduler.schedule(builder(context, id).setMinimumLatency(Math.max(0, due - now()))
                .setRequiresCharging(charging).setExtras(extras).build()) == JobScheduler.RESULT_SUCCESS;
        if (queued) state(context).edit().putLong("last_schedule_ms", now()).commit();
        return queued;
    }
    static void started(android.app.job.JobParameters parameters) { activeJob = parameters.getJobId(); }
    static void released(android.app.job.JobParameters parameters) {
        if (activeJob == parameters.getJobId()) activeJob = 0;
    }
    static void collided(android.app.job.JobParameters parameters) {
        if (manual(parameters)) { MANUAL_PENDING.set(true); retiringManual = parameters.getJobId(); }
        else { automaticPending = true; retiringAutomatic = parameters.getJobId(); }
    }
    private static JobInfo pendingAutomatic(Context context, int avoid) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return null;
        for (int id : new int[] {AUTO_JOB, AUTO_NEXT_JOB}) {
            if (id == activeJob || id == avoid || id == retiringAutomatic) continue;
            JobInfo job = scheduler.getPendingJob(id);
            if (job != null) return job;
        }
        return null;
    }
    private static boolean scheduleAutomatic(Context context, long earliest, boolean charging, int avoid, boolean replace) {
        if (IN_FLIGHT.get()) { automaticPending = true; return true; }
        JobInfo pending = pendingAutomatic(context, avoid);
        if (pending != null && !replace && pending.getExtras().getLong("due_ms",
                WeatherRefreshPolicy.after(now(), pending.getMinLatencyMillis())) <= automaticTime(context, earliest)) return true;
        int id = pending != null ? pending.getId() : (avoid == AUTO_JOB || activeJob == AUTO_JOB
                || retiringAutomatic == AUTO_JOB ? AUTO_NEXT_JOB : AUTO_JOB);
        boolean queued = schedule(context, id, earliest, false, charging);
        if (queued) {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            for (int other : new int[] {AUTO_JOB, AUTO_NEXT_JOB}) {
                if (other != id && other != activeJob && other != avoid && other != retiringAutomatic && scheduler.getPendingJob(other) != null)
                    scheduler.cancel(other);
            }
        }
        return queued;
    }
    private static boolean scheduleManual(Context context, int avoid) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return false;
        for (int id : new int[] {MANUAL_JOB, MANUAL_NEXT_JOB}) {
            if (id != avoid && id != activeJob && scheduler.getPendingJob(id) != null) return true;
        }
        int id = avoid == MANUAL_JOB || activeJob == MANUAL_JOB ? MANUAL_NEXT_JOB : MANUAL_JOB;
        return schedule(context, id, now(), true, false);
    }
    /** Old IDs are aliases only; cancellation must never create a successor in onStopJob. */
    private static void migrateLegacy(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        long earliest = Long.MAX_VALUE; boolean charging = true, found = false, localManual = false;
        for (int id : new int[] {PERIODIC_JOB, REQUEST_JOB, ORIGINAL_JOB}) {
            JobInfo old = scheduler.getPendingJob(id);
            if (old == null || id == activeJob) continue;
            if (old.getExtras().getBoolean(MANUAL, false)) localManual = true;
            else {
                found = true;
                earliest = Math.min(earliest, old.getExtras().getLong(EARLIEST,
                        WeatherRefreshPolicy.after(now(), old.getMinLatencyMillis())));
                charging &= old.getExtras().getBoolean(CHARGING, old.isRequireCharging());
            }
            scheduler.cancel(id);
        }
        if (localManual) {
            if (IN_FLIGHT.get()) MANUAL_PENDING.set(true); else scheduleManual(context, 0);
        }
        if (found) scheduleAutomatic(context, earliest, charging, 0, false);
    }
    /** Successors use the other ID: late Android completion cannot remove new work. */
    static void completed(Context context, android.app.job.JobParameters parameters, boolean retry) {
        if (!networkAllowed(context)) return;
        long time = now();
        long next = WeatherRefreshPolicy.after(time, retry ? BASE_BACKOFF_MS : intervalMs(context));
        if (manual(parameters)) retiringManual = parameters.getJobId();
        else if (parameters.getJobId() == AUTO_JOB || parameters.getJobId() == AUTO_NEXT_JOB)
            retiringAutomatic = parameters.getJobId();
        state(context).edit().putLong("next_periodic_ms", next).commit();
        boolean requested = automaticPending; automaticPending = false;
        if (requested || (automaticCurrent(context, parameters) && backgroundNeeded(context)))
            scheduleAutomatic(context, next, parameters.getExtras().getBoolean(CHARGING, false), parameters.getJobId(), true);
        if (MANUAL_PENDING.getAndSet(false)) scheduleManual(context, retiringManual);
        released(parameters);
        if (WeatherWidgetProvider.hasWidgets(context)) {
            try { WeatherWidgetProvider.updateAll(context); }
            catch (RuntimeException error) { Log.w("WeatherRefresh", "Cannot redraw completed weather job", error); }
        }
    }
    /** Used when a persisted/legacy job becomes ineligible after time or selection changes. */
    static void defer(Context context, android.app.job.JobParameters parameters, boolean collision) {
        if (!automaticCurrent(context, parameters)) return;
        long earliest = Math.max(earliest(parameters), now());
        if (manual(parameters)) scheduleManual(context, parameters.getJobId());
        else {
            retiringAutomatic = parameters.getJobId();
            scheduleAutomatic(context, earliest, parameters.getExtras().getBoolean(CHARGING, false), parameters.getJobId(), false);
        }
    }
    public static void ensureScheduled(Context context) {
        if (!backgroundNeeded(context) || !ready(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        migrateLegacy(context);
        boolean intervalChanged=state(context).getInt(SCHEDULED_INTERVAL,0)!=intervalMinutes(context);
        long next=applyIntervalPolicy(context);
        scheduleAutomatic(context, next, false, 0, intervalChanged);
    }
    /** BOOT, TIME_SET and TIMEZONE_CHANGED callers re-evaluate wall-clock eligibility. */
    public static void reschedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || !ready(context)) return;
        long time = now();
        SharedPreferences saved = state(context);
        boolean rollback = saved.getLong("last_attempt_ms", 0) > time || saved.getLong("last_success_ms", 0) > time
                || saved.getLong("last_schedule_ms", 0) > time;
        if (rollback) saved.edit().putLong("next_attempt_ms", 0).putLong("next_periodic_ms", time).commit();
        migrateLegacy(context);
        if (IN_FLIGHT.get()) { automaticPending = true; return; }
        JobInfo pending = pendingAutomatic(context, 0);
        if (pending != null) scheduleAutomatic(context, rollback ? time : pending.getExtras().getLong(EARLIEST, time),
                pending.getExtras().getBoolean(CHARGING, false), 0, true);
        ensureScheduled(context);
    }
    private static JobInfo.Builder builder(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, WeatherRefreshJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(BASE_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(context.checkSelfPermission("android.permission.RECEIVE_BOOT_COMPLETED") == PackageManager.PERMISSION_GRANTED);
    }
    public static void cancel(Context context) {
        MANUAL_PENDING.set(false);
        automaticPending = false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) for (int id : new int[] {PERIODIC_JOB, REQUEST_JOB, ORIGINAL_JOB,
                AUTO_JOB, AUTO_NEXT_JOB, MANUAL_JOB, MANUAL_NEXT_JOB}) scheduler.cancel(id);
    }
    /** Disable an automatic chain without discarding a real manual request or its active fetch. */
    public static void cancelAutomatic(Context context) {
        automaticPending = false;
        SharedPreferences saved=state(context);
        saved.edit().putLong(AUTO_EPOCH,saved.getLong(AUTO_EPOCH,0)+1).commit();
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler==null)return;
        for(int id:new int[]{PERIODIC_JOB,REQUEST_JOB,ORIGINAL_JOB,AUTO_JOB,AUTO_NEXT_JOB}) {
            if(id==activeJob)continue; // Completion checks the epoch; preserve a queued manual intent.
            JobInfo job=scheduler.getPendingJob(id);
            if(job!=null&&!job.getExtras().getBoolean(MANUAL,false))scheduler.cancel(id);
        }
    }
    private static boolean ready(Context context) {
        if (!networkAllowed(context)) { blocked(context, "network_consent_required"); return false; }
        if (sourceClass(context).isEmpty()) { blocked(context, "source_not_configured"); return false; }
        return true;
    }
    static String sourceClass(Context context) {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metadata = app.metaData;
            return metadata == null ? "" : metadata.getString(SOURCE_METADATA, "");
        } catch (Exception error) { return ""; }
    }
    static WeatherRefreshSource source(Context context) throws Exception {
        String name = sourceClass(context);
        if (name.isEmpty()) return null;
        if (name.startsWith(".")) name = context.getPackageName() + name;
        return (WeatherRefreshSource) Class.forName(name, true, context.getClassLoader()).getDeclaredConstructor().newInstance();
    }
    static void attempted(Context context) { state(context).edit().putLong("last_attempt_ms", now()).commit(); }
    static void blocked(Context context, String reason) {
        state(context).edit().putString("last_error", safeReason(reason)).commit();
    }
    static void failed(Context context, String reason) {
        SharedPreferences state = state(context);
        int failures = Math.min(12, state.getInt("consecutive_failures", 0) + 1);
        long delay = WeatherRefreshPolicy.retryDelay(failures);
        state.edit().putInt("consecutive_failures", failures)
            .putLong("next_attempt_ms", WeatherRefreshPolicy.after(now(), delay))
            .putString("last_error", safeReason(reason)).commit();
    }
    private static String safeReason(String reason) {
        // Sources should supply a short category, never URLs, keys, or sensitive response bodies.
        return reason == null ? "source_error" : reason.substring(0, Math.min(120, reason.length()));
    }
}
