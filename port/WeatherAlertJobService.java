package dev.weather.oneplus;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Cached daily reminders also run when no widget or Activity is alive. */
public final class WeatherAlertJobService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private JobParameters active;private Future<?> work;
    @Override public boolean onStartJob(JobParameters parameters) {
        active=parameters;WeatherAlerts.jobStarted(this,parameters.getJobId());
        android.util.Log.i("WeatherAlerts","Background reminder started");
        work=worker.submit(()->{
            try {
                if(!Thread.currentThread().isInterrupted())WeatherRefresh.requestAutomatic(getApplicationContext());
                WeatherAlerts.runDue(getApplicationContext());
            } finally {
                main.post(()->{if(active==parameters){active=null;WeatherAlerts.jobEnded(parameters.getJobId());jobFinished(parameters,false);}});
            }
        });return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        if(active!=parameters)return false;
        active=null;if(work!=null)work.cancel(true);WeatherAlerts.jobEnded(parameters.getJobId());
        if(android.os.Build.VERSION.SDK_INT>=31&&(parameters.getStopReason()==JobParameters.STOP_REASON_CANCELLED_BY_APP
                ||parameters.getStopReason()==JobParameters.STOP_REASON_USER))return false;
        // Keep this slot retryable inside its useful window. The other ID already
        // represents the next 7/19 slot, not a retry for this interrupted one.
        return WeatherAlerts.retryDue(this,System.currentTimeMillis());
    }
    @Override public void onDestroy(){if(work!=null)work.cancel(true);if(active!=null)WeatherAlerts.jobEnded(active.getJobId());active=null;worker.shutdownNow();super.onDestroy();}
}
