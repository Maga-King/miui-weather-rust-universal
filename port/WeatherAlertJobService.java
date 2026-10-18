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
        work=worker.submit(()->{
            try {
                WeatherAlerts.runDue(getApplicationContext());
                if(!Thread.currentThread().isInterrupted())WeatherRefresh.requestAutomatic(getApplicationContext());
            } finally {
                main.post(()->{if(active==parameters){active=null;WeatherAlerts.jobEnded(parameters.getJobId());jobFinished(parameters,false);}});
            }
        });return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        if(active==parameters){active=null;if(work!=null)work.cancel(true);WeatherAlerts.jobEnded(parameters.getJobId());}
        return false; // Successor was scheduled before work, using the other job ID.
    }
    @Override public void onDestroy(){if(work!=null)work.cancel(true);if(active!=null)WeatherAlerts.jobEnded(active.getJobId());active=null;worker.shutdownNow();super.onDestroy();}
}
