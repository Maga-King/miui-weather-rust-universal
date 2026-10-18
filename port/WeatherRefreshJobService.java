package dev.weather.oneplus;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.CancellationSignal;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Standard Android background job; it cannot require a running Activity or Flutter surface. */
public final class WeatherRefreshJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, Work> jobs = new HashMap<>();
    private static final long TIMEOUT_MS = 60_000;
    private static final class Work {
        final JobParameters parameters;
        final CancellationSignal cancellation = new CancellationSignal();
        Future<?> future;
        Runnable timeout;
        Work(JobParameters parameters) { this.parameters = parameters; }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        if (!WeatherRefresh.networkAllowed(this)) { WeatherRefresh.blocked(this, "network_consent_required"); return false; }
        if (!WeatherRefresh.automaticCurrent(this, parameters)) return false;
        if (!WeatherRefresh.manual(parameters) && WeatherRefresh.automaticDelay(this, WeatherRefresh.earliest(parameters)) > 0) {
            // JobScheduler stores elapsed-time delays; a persisted job or a timezone
            // change must still pass the current local wall-clock policy before HTTP.
            main.post(() -> WeatherRefresh.defer(this, parameters, false));
            return false;
        }
        if (!WeatherRefresh.IN_FLIGHT.compareAndSet(false, true)) {
            // The active request might belong to a different city/unit. Preserve a
            // successor, applying freshness/quiet hours again after that work finishes.
            WeatherRefresh.collided(parameters);
            return false;
        }
        WeatherRefresh.started(parameters);
        Work work = new Work(parameters);
        jobs.put(parameters.getJobId(), work);
        work.timeout = () -> {
            if (jobs.get(parameters.getJobId()) != work) return;
            work.cancellation.cancel();
            if (work.future != null) work.future.cancel(true);
            WeatherRefresh.failed(this, "source_timeout");
            finish(work, true);
        };
        main.postDelayed(work.timeout, TIMEOUT_MS);
        work.future = worker.submit(() -> {
            WeatherRefreshSource.Result outcome;
            WeatherDatabaseBridge.City selection = null;
            try {
                WeatherRefreshSource source = WeatherRefresh.source(this);
                if (source == null) outcome = WeatherRefreshSource.Result.blocked("source_not_configured");
                else if (!WeatherRefresh.automaticCurrent(this, parameters))
                    outcome = WeatherRefreshSource.Result.blocked("automatic_cancelled");
                else if (!WeatherRefresh.manual(parameters)
                        && WeatherRefresh.automaticDelay(this, WeatherRefresh.earliest(parameters)) > 0)
                    outcome = WeatherRefreshSource.Result.blocked("automatic_deferred");
                else {
                    WeatherRefresh.attempted(this);
                    selection = WeatherDatabaseBridge.firstCity(this);
                    outcome = source.fetch(getApplicationContext(), WeatherWidgetCache.read(this), work.cancellation);
                    if (outcome == null) outcome = WeatherRefreshSource.Result.failed("empty_source_response", true);
                }
            } catch (Exception error) {
                // Error types only: provider URLs or keys must not enter widget state/logs.
                outcome = WeatherRefreshSource.Result.failed(error.getClass().getSimpleName(), true);
            }
            WeatherRefreshSource.Result result = outcome;
            WeatherDatabaseBridge.City requestedCity = selection;
            main.post(() -> {
                if (jobs.get(parameters.getJobId()) != work || work.cancellation.isCanceled()) return;
                if (!WeatherRefresh.networkAllowed(this)) { finish(work, false); return; }
                if (!WeatherRefresh.automaticCurrent(this, parameters)) { finish(work, false); return; }
                boolean retry = result.retryable;
                if (result.networkVerified) {
                    // Selection can change after the source's final check while this
                    // completion waits in the main queue. Never overwrite that city's cache.
                    try {
                        if (requestedCity == null || !requestedCity.sameSelection(WeatherDatabaseBridge.firstCity(this))
                                || !requestedCity.id.equals(result.observation.cityId)
                                || !requestedCity.unit.equals(result.observation.unit)) {
                            WeatherRefresh.failed(this, "selected_city_changed");
                            finish(work, true); return;
                        }
                    } catch (Exception error) {
                        WeatherRefresh.failed(this, error.getClass().getSimpleName());
                        finish(work, true); return;
                    }
                    if (!WeatherRefresh.publishNetworkSuccess(this, result.observation)) {
                        WeatherRefresh.failed(this, "cache_write_failed"); retry = true;
                    }
                    if(result.rawResponse!=null)WeatherAlerts.onWeatherResponse(this,requestedCity,result.rawResponse);
                } else if ("automatic_deferred".equals(result.reason)) {
                    finish(work, true); return;
                } else if (retry) WeatherRefresh.failed(this, result.reason);
                else WeatherRefresh.blocked(this, result.reason);
                finish(work, retry);
            });
        });
        return true;
    }
    private void finish(Work work, boolean retry) {
        if (jobs.remove(work.parameters.getJobId()) != work) return;
        main.removeCallbacks(work.timeout);
        WeatherRefresh.IN_FLIGHT.set(false);
        // Explicit successors preserve the local quiet window even after a manual
        // attempt fails; Android's retry would retain its manual-bypass extras.
        jobFinished(work.parameters, false);
        WeatherRefresh.completed(this, work.parameters, retry);
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Work work = jobs.get(parameters.getJobId());
        // A delayed stop for an older generation must never cancel current work.
        if (work == null || work.parameters != parameters) return false;
        jobs.remove(parameters.getJobId());
        main.removeCallbacks(work.timeout); work.cancellation.cancel();
        if (work.future != null) work.future.cancel(true);
        WeatherRefresh.IN_FLIGHT.set(false); WeatherRefresh.released(parameters);
        if (!WeatherRefresh.networkAllowed(this)) return false;
        if (!WeatherRefresh.automaticCurrent(this, parameters)) return false;
        if (Build.VERSION.SDK_INT >= 31 && (parameters.getStopReason() == JobParameters.STOP_REASON_CANCELLED_BY_APP
                || parameters.getStopReason() == JobParameters.STOP_REASON_USER)) return false;
        // Android retains/retries its automatic job. Only an interrupted manual
        // attempt needs a different automatic successor to remove the manual bypass.
        if (WeatherRefresh.manual(parameters)) {
            main.post(() -> WeatherRefresh.completed(this, parameters, true)); return false;
        }
        return WeatherRefresh.backgroundNeeded(this);
    }
    @Override public void onDestroy() {
        for (Work work : jobs.values()) {
            main.removeCallbacks(work.timeout); work.cancellation.cancel();
            if (work.future != null) work.future.cancel(true);
            WeatherRefresh.released(work.parameters);
        }
        jobs.clear(); WeatherRefresh.IN_FLIGHT.set(false); worker.shutdownNow(); super.onDestroy();
    }
}
