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
import android.widget.FrameLayout;

/** Android surface supplied to Xiaomi's original Majestic weather renderer. */
public final class MajesticSurface implements SurfaceHolder.Callback,AutoCloseable {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SurfaceView view;
    private long generation;
    private boolean available,closed;
    private boolean resumed=true,copyPending;
    private Bitmap glassBackdrop;
    private native void nativeGlassBackdrop(Bitmap pixels);
    private final Runnable copyGlassBackdrop=this::copyGlassBackdrop;
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
        nativeBind();
    }

    public void attach(){if(!closed){resumed=true;nativeBind();scheduleGlassCopy();}}
    public void pause(){resumed=false;main.removeCallbacks(copyGlassBackdrop);}
    private void scheduleGlassCopy(){main.removeCallbacks(copyGlassBackdrop);if(!closed&&resumed)main.postDelayed(copyGlassBackdrop,100);}
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
            view.setVisibility(SurfaceView.VISIBLE);
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
        if(!closed&&generation!=0)nativeWindow(generation,holder.getSurface());
        scheduleGlassCopy();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        available=false;
        main.removeCallbacks(copyGlassBackdrop);
        if(!closed&&generation!=0)nativeWindow(generation,null);
    }
    @Override public void close() {
        if(closed)return;
        closed=true;nativeUnbind();
        main.removeCallbacks(copyGlassBackdrop);
        if(!copyPending&&glassBackdrop!=null){glassBackdrop.recycle();glassBackdrop=null;}
        view.getHolder().removeCallback(this);
    }
}
