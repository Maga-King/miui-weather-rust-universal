package dev.weather.oneplus;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

/** App-local rendering choices. Display votes are requests, never measured FPS. */
public final class WeatherRenderPreferences {
    private static final String FILE="weather_render_v1";
    private static final String NATIVE_RESOLUTION="native_resolution",FORCE_120="force_120";
    private static boolean installed;
    private WeatherRenderPreferences() {}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(FILE,Context.MODE_PRIVATE);}
    public static boolean nativeResolutionEnabled(Context context){return prefs(context).getBoolean(NATIVE_RESOLUTION,true);}
    public static void setNativeResolutionEnabled(Context context,boolean enabled){prefs(context).edit().putBoolean(NATIVE_RESOLUTION,enabled).apply();}
    public static boolean force120Enabled(Context context){return prefs(context).getBoolean(FORCE_120,false);}
    public static void setForce120Enabled(Context context,boolean enabled){prefs(context).edit().putBoolean(FORCE_120,enabled).apply();}

    private static native void nativeRefreshRate(float actualRefreshRate);
    private static native boolean nativeResolution(boolean enabled);

    /** Also covers the original Java detail/settings activities. No global display setting is written. */
    public static void install(Application application){
        if(installed)return;
        installed=true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity activity,Bundle state){requestWindow(activity);}
            public void onActivityResumed(Activity activity){requestWindow(activity);}
            public void onActivityStarted(Activity activity){}
            public void onActivityPaused(Activity activity){}
            public void onActivityStopped(Activity activity){}
            public void onActivitySaveInstanceState(Activity activity,Bundle state){}
            public void onActivityDestroyed(Activity activity){}
        });
    }

    static final class RateChoice {
        final float requested,actual;
        final int modeId;
        RateChoice(float requested,float actual,int modeId){this.requested=requested;this.actual=actual;this.modeId=modeId;}
    }
    private static Display display(Activity activity){
        return activity.getWindow().getDecorView().getDisplay()!=null
            ?activity.getWindow().getDecorView().getDisplay():activity.getWindowManager().getDefaultDisplay();
    }
    private static boolean validRate(float rate){return rate>0&&!Float.isNaN(rate)&&!Float.isInfinite(rate);}
    static RateChoice choose(Activity activity){
        boolean force=force120Enabled(activity);
        float actual=0,best=0;int bestId=0,mode120=0;
        try{
            Display display=display(activity);
            if(display!=null){
                actual=display.getRefreshRate();
                Display.Mode current=display.getMode();
                for(Display.Mode mode:display.getSupportedModes()){
                    // Pin only a mode at the current physical size, including folded/external displays.
                    if(mode.getPhysicalWidth()!=current.getPhysicalWidth()||mode.getPhysicalHeight()!=current.getPhysicalHeight())continue;
                    float rate=mode.getRefreshRate();
                    if(!validRate(rate))continue;
                    if(Math.abs(rate-120)<0.5f)mode120=mode.getModeId();
                    if(rate<=120.5f&&rate>best){best=rate;bestId=mode.getModeId();}
                }
            }
        }catch(RuntimeException error){Log.w("WeatherRender","Cannot read display modes; requesting 120 Hz",error);}
        if(!validRate(actual))actual=0;
        // Unknown capability requests 120. A known 60/90 Hz panel requests its own maximum.
        float target=force?120:best>0?Math.min(best,120):120;
        return new RateChoice(target,actual,force?mode120:bestId);
    }
    public static float requestedRefreshRate(Activity activity){return choose(activity).requested;}
    public static float actualRefreshRate(Activity activity){return choose(activity).actual;}
    public static void requestWindow(Activity activity){requestWindow(activity,choose(activity));}
    private static void requestWindow(Activity activity,RateChoice rate){
        WindowManager.LayoutParams params=activity.getWindow().getAttributes();
        if(params.preferredDisplayModeId==rate.modeId&&Math.abs(params.preferredRefreshRate-rate.requested)<0.01f)return;
        params.preferredDisplayModeId=rate.modeId;
        params.preferredRefreshRate=rate.requested;
        activity.getWindow().setAttributes(params);
        Log.i("WeatherRender","Display request="+rate.requested+" Hz actual="+rate.actual+" Hz mode="+rate.modeId+" force120="+force120Enabled(activity));
    }
    static void requestSurface(Surface surface,float rate){
        if(Build.VERSION.SDK_INT<30||surface==null||!surface.isValid())return;
        try{
            if(Build.VERSION.SDK_INT>=31)surface.setFrameRate(rate,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            else surface.setFrameRate(rate,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }catch(IllegalArgumentException|IllegalStateException error){Log.w("WeatherRender","Surface refresh request unavailable",error);}
    }

    /** Owned by MajesticSurface, whose existing lifecycle follows WeatherActivity. */
    static final class Session implements AutoCloseable,DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener,SurfaceHolder.Callback {
        private final Activity activity;
        private final SurfaceView flutter,animation;
        private final Runnable resolutionChanged;
        private final DisplayManager manager;
        private final SharedPreferences preferences;
        private final Handler main=new Handler(Looper.getMainLooper());
        private boolean resumed,closed;
        private float requested;
        Session(Activity activity,SurfaceView flutter,SurfaceView animation,Runnable resolutionChanged){
            this.activity=activity;this.flutter=flutter;this.animation=animation;this.resolutionChanged=resolutionChanged;
            manager=activity.getSystemService(DisplayManager.class);preferences=prefs(activity);
            flutter.getHolder().addCallback(this);
            resume();
        }
        void resume(){
            if(closed)return;
            if(!resumed){resumed=true;preferences.registerOnSharedPreferenceChangeListener(this);if(manager!=null)manager.registerDisplayListener(this,main);}
            apply();
        }
        void pause(){
            if(!resumed)return;
            resumed=false;preferences.unregisterOnSharedPreferenceChangeListener(this);if(manager!=null)manager.unregisterDisplayListener(this);
            requestSurface(flutter.getHolder().getSurface(),0);requestSurface(animation.getHolder().getSurface(),0);
        }
        void apply(){
            if(closed||!resumed)return;
            RateChoice rate=choose(activity);requested=rate.requested;
            requestWindow(activity,rate);
            requestSurface(flutter.getHolder().getSurface(),requested);requestSurface(animation.getHolder().getSurface(),requested);
            // Keep actual display refresh as diagnostics. The engine's compatibility
            // report is 120 Hz; NDK vsync callbacks/timestamps remain physical.
            nativeRefreshRate(rate.actual);
            if(nativeResolution(nativeResolutionEnabled(activity)))resolutionChanged.run();
        }
        void animationSurfaceChanged(){if(resumed)requestSurface(animation.getHolder().getSurface(),requested);}
        @Override public void onDisplayAdded(int id){apply();}
        @Override public void onDisplayRemoved(int id){apply();}
        @Override public void onDisplayChanged(int id){apply();}
        @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){if(NATIVE_RESOLUTION.equals(key)||FORCE_120.equals(key))main.post(this::apply);}
        @Override public void surfaceCreated(SurfaceHolder holder){apply();}
        @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){apply();}
        @Override public void surfaceDestroyed(SurfaceHolder holder){}
        @Override public void close(){if(closed)return;pause();closed=true;flutter.getHolder().removeCallback(this);}
    }
}
