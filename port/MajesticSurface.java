package dev.weather.oneplus;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Choreographer;
import android.widget.FrameLayout;

/** Android surface supplied to Xiaomi's original Majestic weather renderer. */
public final class MajesticSurface implements SurfaceHolder.Callback,AutoCloseable {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Choreographer choreographer=Choreographer.getInstance();
    private final SurfaceView view;
    private final Activity activity;
    private final WeatherRenderPreferences.Session renderSession;
    private long generation;
    private boolean available,closed;
    private boolean resumed=true,copyPending,copyScheduled;
    private boolean startupPending=true;
    private Runnable firstBackdropReady;
    private Bitmap glassBackdrop;
    private native void nativeGlassBackdrop(Bitmap pixels);
    private final Choreographer.FrameCallback copyGlassBackdrop=frameTimeNanos->copyGlassBackdrop();
    private native void nativeBind();
    private native void nativeUnbind();
    private native void nativeWindow(long generation,Surface surface);

    public MajesticSurface(Activity activity,FrameLayout root,SurfaceView flutter) {
        this.activity=activity;
        view=new SurfaceView(activity);
        view.setFocusable(false);view.setClickable(false);
        view.getHolder().setFormat(PixelFormat.RGB_888);
        view.getHolder().addCallback(this);
        root.addView(view,0,new FrameLayout.LayoutParams(-1,-1));
        flutter.setZOrderMediaOverlay(true);
        flutter.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        renderSession=new WeatherRenderPreferences.Session(activity,flutter,view);
        nativeBind();
    }

    public void attach(){if(!closed){resumed=true;renderSession.resume();nativeBind();scheduleGlassCopy();}}
    public void pause(){resumed=false;renderSession.pause();choreographer.removeFrameCallback(copyGlassBackdrop);copyScheduled=false;}
    public void prepareStartupBackdrop(Runnable ready){
        if(closed||generation==0||!available){ready.run();return;}
        firstBackdropReady=ready;startupPending=false;scheduleGlassCopy();
    }
    public void finishStartup(){startupPending=false;firstBackdropReady=null;scheduleGlassCopy();}
    private void scheduleGlassCopy(){
        // One copy in flight; sample the next physical display frame after it
        // completes. No fixed 100 ms cap, accumulated work, or synthetic timer.
        if(!closed&&resumed&&!startupPending&&!copyPending&&!copyScheduled){
            copyScheduled=true;
            choreographer.postFrameCallback(copyGlassBackdrop);
        }
    }
    private void copyGlassBackdrop(){
        copyScheduled=false;
        if(closed||!resumed)return;
        if(copyPending)return;
        if(!available||generation==0||!view.isShown()||!view.getHolder().getSurface().isValid()){scheduleGlassCopy();return;}
        int width=view.getWidth(),height=view.getHeight();
        if(width<=0||height<=0){scheduleGlassCopy();return;}
        if(glassBackdrop==null||glassBackdrop.getWidth()!=width||glassBackdrop.getHeight()!=height){
            if(glassBackdrop!=null)glassBackdrop.recycle();
            glassBackdrop=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        }
        final Bitmap frame=glassBackdrop;
        final long request=generation;
        copyPending=true;
        try{PixelCopy.request(view,frame,result->{
            copyPending=false;
            if(!closed&&resumed&&available&&generation==request&&result==PixelCopy.SUCCESS){
                nativeGlassBackdrop(frame);
                Runnable ready=firstBackdropReady;firstBackdropReady=null;
                if(ready!=null)ready.run();
            }
            if(closed){frame.recycle();glassBackdrop=null;}else scheduleGlassCopy();
        },main);}catch(IllegalArgumentException error){copyPending=false;scheduleGlassCopy();}
    }

    // Called by native code on a Dart or rendering thread.
    public void requestSurface(long request,int width,int height,float x,float y) {
        main.post(()->{
            if(closed)return;
            long previousGeneration=generation;
            generation=request;
            if(activity instanceof WeatherActivity)((WeatherActivity)activity).onWeatherSceneRequested();
            FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)view.getLayoutParams();
            int nextWidth=width>0?width:-1,nextHeight=height>0?height:-1;
            int nextLeft=Math.round(x),nextTop=Math.round(y);
            boolean layoutChanged=params.width!=nextWidth||params.height!=nextHeight||
                params.leftMargin!=nextLeft||params.topMargin!=nextTop;
            if(layoutChanged){
                params.width=nextWidth;params.height=nextHeight;
                params.leftMargin=nextLeft;params.topMargin=nextTop;
                view.setLayoutParams(params);
            }
            if(view.getVisibility()!=SurfaceView.VISIBLE)view.setVisibility(SurfaceView.VISIBLE);
            if(layoutChanged||previousGeneration!=request)
                Log.i("WeatherMGL","Requested original animation surface "+width+"x"+height+" generation="+request);
            if(available&&view.getHolder().getSurface().isValid())
                nativeWindow(generation,view.getHolder().getSurface());
            scheduleGlassCopy();
        });
    }

    public void hideSurface(long request) {
        main.post(()->{
            if(closed||generation!=request)return;
            generation=0;
            if(view.getVisibility()!=SurfaceView.INVISIBLE)view.setVisibility(SurfaceView.INVISIBLE);
        });
    }

    @Override public void surfaceCreated(SurfaceHolder holder){available=true;}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {
        available=true;
        renderSession.animationSurfaceChanged();
        if(!closed&&generation!=0)nativeWindow(generation,holder.getSurface());
        scheduleGlassCopy();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        available=false;
        choreographer.removeFrameCallback(copyGlassBackdrop);
        copyScheduled=false;
        if(!closed&&generation!=0)nativeWindow(generation,null);
    }
    @Override public void close() {
        if(closed)return;
        closed=true;firstBackdropReady=null;renderSession.close();nativeUnbind();
        choreographer.removeFrameCallback(copyGlassBackdrop);
        copyScheduled=false;
        if(!copyPending&&glassBackdrop!=null){glassBackdrop.recycle();glassBackdrop=null;}
        view.getHolder().removeCallback(this);
    }
}
