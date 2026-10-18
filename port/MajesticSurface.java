package dev.weather.oneplus;

import android.app.Activity;
import android.graphics.PixelFormat;
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

    public void attach(){if(!closed)nativeBind();}

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
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        available=false;
        if(!closed&&generation!=0)nativeWindow(generation,null);
    }
    @Override public void close() {
        if(closed)return;
        closed=true;nativeUnbind();
        view.getHolder().removeCallback(this);
    }
}
