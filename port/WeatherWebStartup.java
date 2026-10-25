package dev.weather.oneplus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Initialize Chromium through its supported asynchronous API, once per process. */
final class WeatherWebStartup {
    interface Failure { void onFailure(Throwable error); }
    private static final class Request {
        final Runnable ready;final Failure failure;
        Request(Runnable ready,Failure failure){this.ready=ready;this.failure=failure;}
    }
    private static final ArrayList<Request> waiting=new ArrayList<>();
    private static boolean started,ready;
    private static Throwable startupError;
    private static long began;
    private static ExecutorService worker;
    private static final Handler main=new Handler(Looper.getMainLooper());
    private WeatherWebStartup(){}
    static void whenReady(Context context,Runnable callback,Failure failure){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Call on main thread");
        if(ready){callback.run();return;}
        if(startupError!=null){failure.onFailure(startupError);return;}
        waiting.add(new Request(callback,failure));if(started)return;started=true;began=SystemClock.elapsedRealtime();
        Context app=context.getApplicationContext();
        worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"WeatherWebStartup");t.setDaemon(true);return t;});
        try{
            WebViewCompat.startUpWebView(app,new WebViewStartUpConfig.Builder(worker).build(),
                    new WebViewOutcomeReceiver<WebViewStartUpResult,WebViewStartupException>(){
                public void onResult(WebViewStartUpResult result){
                    Log.i("WeatherWebStartup","ready elapsed="+(SystemClock.elapsedRealtime()-began)
                            +"ms uiTotal="+result.getTotalTimeInUiThreadMillis()+"ms maxTask="+result.getMaxTimePerTaskInUiThreadMillis()+"ms");
                    complete();
                }
                public void onError(WebViewStartupException error){fail(error);}
            });
        }catch(RuntimeException|LinkageError error){fail(error);}
    }
    private static void fail(Throwable error){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->fail(error));return;}
        if(startupError!=null||ready)return;
        startupError=error;
        Log.e("WeatherWebStartup","Asynchronous WebView initialization failed",error);
        ArrayList<Request> pending=new ArrayList<>(waiting);waiting.clear();
        if(worker!=null){worker.shutdown();worker=null;}
        for(Request request:pending)request.failure.onFailure(error);
    }
    private static void complete(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(WeatherWebStartup::complete);return;}
        if(ready)return;ready=true;
        ArrayList<Request> pending=new ArrayList<>(waiting);waiting.clear();
        if(worker!=null){worker.shutdown();worker=null;}
        for(Request request:pending)request.ready.run();
    }
}
