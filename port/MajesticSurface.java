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
    private final WeatherRenderPreferences.Session renderSession;
    private long generation;
    private boolean available,closed;
    private boolean resumed=true,copyPending;
    private boolean resolutionRecreationPending;
    private Bitmap glassBackdrop;
    private native void nativeGlassBackdrop(Bitmap pixels);
    private final Choreographer.FrameCallback copyGlassBackdrop=frameTimeNanos->copyGlassBackdrop();
    private native void nativeBind();
    private native void nativeUnbind();
    private native void nativeWindow(long generation,Surface surface);

    public MajesticSurface(Activity activity,FrameLayout root,SurfaceView flutter) {
        view=new SurfaceView(activity);
        view.setFocusable(false);view.setClickable(false);
        view.getHolder().setFormat(PixelFormat.RGB_888);
        view.getHolder().addCallback(this);
        root.addView(view,0,new FrameLayout.LayoutParams(-1,-1));
        flutter.setZOrderMediaOverlay(true);
        flutter.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        renderSession=new WeatherRenderPreferences.Session(activity,flutter,view,this::recreateResolutionSurface);
        nativeBind();
    }

    public void attach(){if(!closed){resumed=true;renderSession.resume();nativeBind();scheduleGlassCopy();}}
    public void pause(){resumed=false;renderSession.pause();choreographer.removeFrameCallback(copyGlassBackdrop);}
    private void recreateResolutionSurface(){
        if(closed||!available||generation==0||resolutionRecreationPending)return;
        // A real Surface lifecycle makes nativeWindowCreated rebuild the original
        // scene's render targets using the new render scale. Layout size is unchanged.
        resolutionRecreationPending=true;
        view.setVisibility(SurfaceView.INVISIBLE);
    }
    private void scheduleGlassCopy(){
        choreographer.removeFrameCallback(copyGlassBackdrop);
        // One copy in flight; sample the next physical display frame after it
        // completes. No fixed 100 ms cap, accumulated work, or synthetic timer.
        if(!closed&&resumed)choreographer.postFrameCallback(copyGlassBackdrop);
    }
    private void copyGlassBackdrop(){
        if(closed||!resumed)return;
        if(copyPending||!available||generation==0||!view.isShown()||!view.getHolder().getSurface().isValid()){scheduleGlassCopy();return;}
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
            if(!closed&&resumed&&available&&generation==request&&result==PixelCopy.SUCCESS)nativeGlassBackdrop(frame);
            if(closed){frame.recycle();glassBackdrop=null;}else scheduleGlassCopy();
        },main);}catch(IllegalArgumentException error){copyPending=false;scheduleGlassCopy();}
    }

    // Called by native code on a Dart or rendering thread.
    public void requestSurface(long request,int width,int height,float x,float y) {
        main.post(()->{
            if(closed)return;
            generation=request;
            FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)view.getLayoutParams();
            params.width=width>0?width:-1;params.height=height>0?height:-1;
            params.leftMargin=Math.round(x);params.topMargin=Math.round(y);
            view.setLayoutParams(params);
            if(!resolutionRecreationPending)view.setVisibility(SurfaceView.VISIBLE);
            Log.i("WeatherMGL","Requested original animation surface "+width+"x"+height+" generation="+request);
            if(available&&view.getHolder().getSurface().isValid())
                nativeWindow(generation,view.getHolder().getSurface());
            scheduleGlassCopy();
        });
    }

    public void hideSurface(long request) {
        main.post(()->{
            if(closed||generation!=request)return;
            generation=0;view.setVisibility(SurfaceView.INVISIBLE);
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
        if(!closed&&generation!=0)nativeWindow(generation,null);
        if(resolutionRecreationPending){
            resolutionRecreationPending=false;
            main.post(()->{if(!closed&&generation!=0)view.setVisibility(SurfaceView.VISIBLE);});
        }
    }
    @Override public void close() {
        if(closed)return;
        closed=true;renderSession.close();nativeUnbind();
        choreographer.removeFrameCallback(copyGlassBackdrop);
        if(!copyPending&&glassBackdrop!=null){glassBackdrop.recycle();glassBackdrop=null;}
        view.getHolder().removeCallback(this);
    }
}
