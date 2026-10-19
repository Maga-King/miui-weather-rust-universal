package dev.weather.oneplus;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.WeakHashMap;

/** App text preference. Widget and symbol fonts retain their original design. */
public final class WeatherFonts {
    private static Boolean engineMiSans;
    private static final WeakHashMap<TextView,OriginalStyle> originalStyles=new WeakHashMap<>();
    private static final class OriginalStyle {
        final int weight;final boolean italic;final Typeface face;
        OriginalStyle(Typeface face,int adjustment) {
            this.face=face==null?Typeface.DEFAULT:face;
            weight=Math.max(1,Math.min(1000,(face==null?400:face.getWeight())-adjustment));
            italic=face!=null&&face.isItalic();
        }
    }
    private WeatherFonts() {}
    public static boolean miSansEnabled(Context context) {
        return context.getSharedPreferences("weather_appearance_v1",Context.MODE_PRIVATE).getBoolean("mi_sans",true);
    }
    public static void setMiSansEnabled(Context context,boolean enabled) {
        if(!context.getSharedPreferences("weather_appearance_v1",Context.MODE_PRIVATE).edit().putBoolean("mi_sans",enabled).commit())
            throw new IllegalStateException("Could not save font choice");
    }
    public static boolean followsSystemWeight(Context context) {
        return context.getSharedPreferences("weather_appearance_v1",Context.MODE_PRIVATE).getBoolean("font_weight_follow_system",true);
    }
    public static void setFollowsSystemWeight(Context context,boolean follow) {
        if(!context.getSharedPreferences("weather_appearance_v1",Context.MODE_PRIVATE).edit().putBoolean("font_weight_follow_system",follow).commit())
            throw new IllegalStateException("Could not save font weight mode");
    }
    public static int weightProgress(Context context) {
        return Math.max(0,Math.min(100,context.getSharedPreferences("weather_appearance_v1",Context.MODE_PRIVATE).getInt("font_weight",50)));
    }
    public static void setWeightProgress(Context context,int progress) {
        if(progress<0||progress>100)throw new IllegalArgumentException("Font weight outside slider range");
        if(!context.getSharedPreferences("weather_appearance_v1",Context.MODE_PRIVATE).edit().putInt("font_weight",progress).commit())
            throw new IllegalStateException("Could not save font weight");
    }
    static int systemAdjustment(Context context) {
        if(Build.VERSION.SDK_INT<31)return 0;
        int value=context.getResources().getConfiguration().fontWeightAdjustment;
        return value==Integer.MAX_VALUE?0:Math.max(-300,Math.min(300,value));
    }
    static boolean systemBold(Context context) {return followsSystemWeight(context)&&systemAdjustment(context)>=300;}
    static float weightScale(Context context) {
        if(!followsSystemWeight(context))return .5f+weightProgress(context)/100f;
        int adjustment=systemAdjustment(context);
        // Android's accessibility bold flag changes Flutter's styles; do not also
        // multiply the font weight and apply the same adjustment twice.
        if(adjustment>=300)return 1f;
        if(adjustment!=0)return Math.max(.5f,Math.min(1.75f,1f+adjustment/400f));
        int scale=Settings.System.getInt(context.getContentResolver(),"key_miui_font_weight_scale",-1);
        if(scale<0)scale=Settings.Global.getInt(context.getContentResolver(),"key_miui_font_weight_scale",-1);
        if(scale>=0)return .5f+Math.max(0,Math.min(100,scale))/100f;
        // ColorOS/OxygenOS stores the active font mode in the upper nibble and
        // the user's VF weight in the lower 12 bits. Ignore disabled/unknown modes.
        int variation=Settings.System.getInt(context.getContentResolver(),"font_variation_settings",-1);
        if(variation>=0) {
            int mode=(variation>>12)&15,weight=variation&4095;
            if((mode==1||mode==2||mode==3||mode==5)&&weight>=100&&weight<=1000)
                return Math.max(.5f,Math.min(1.75f,weight/500f));
        }
        return 1f;
    }
    private static native boolean nativeWeight(float scale,int accessibilityFeatures);
    static boolean syncEngine(Context context) {return nativeWeight(weightScale(context),systemBold(context)?8:0);}
    static void configureEngine(Context context) throws android.system.ErrnoException {
        boolean selected=miSansEnabled(context);
        if(engineMiSans==null)engineMiSans=selected;
        android.system.Os.setenv("WEATHER_SYSTEM_FONT",engineMiSans?"0":"1",true);
    }
    static boolean engineNeedsRestart(Context context){return engineMiSans!=null&&engineMiSans!=miSansEnabled(context);}

    static void apply(Context context,View root) {
        if(root instanceof TextView) {
            TextView text=(TextView)root;
            CharSequence value=text.getText();
            // MIUIX can draw icon glyphs in TextViews; never replace those faces.
            boolean symbol=false;
            for(int i=0;i<value.length();i++)if(Character.getType(value.charAt(i))==Character.PRIVATE_USE){symbol=true;break;}
            if(!symbol) {
                Typeface old=text.getTypeface();
                OriginalStyle original=originalStyles.get(text);
                if(original==null){original=new OriginalStyle(old,systemAdjustment(context));originalStyles.put(text,original);}
                int weight=systemBold(context)?Math.min(1000,original.weight+300):Math.max(100,Math.min(900,Math.round(original.weight*weightScale(context))));
                boolean italic=original.italic;
                Typeface face;
                if(miSansEnabled(context)) {
                    int axis=WeatherTextFonts.miSansAxis(weight);
                    face=WeatherWidgetFonts.face(context,axis,weight);
                    if(italic)face=Typeface.create(face,weight,true);
                } else if(followsSystemWeight(context))face=original.face;
                else face=WeatherTextFonts.systemFace(original.face,original.weight,weight,italic);
                if(old!=face)text.setTypeface(face);
            }
        }
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++)apply(context,group.getChildAt(i));
        }
    }
    public static void apply(Activity activity) {apply(activity,activity.getWindow().getDecorView());}

    /** View-only callbacks: never change Resources, global Typeface or widget Paint. */
    static void install(android.app.Application app) {
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks(){
            final WeakHashMap<Activity,android.view.ViewTreeObserver.OnGlobalLayoutListener> listeners=new WeakHashMap<>();
            public void onActivityCreated(Activity a,android.os.Bundle state){
                View decor=a.getWindow().getDecorView();
                android.view.ViewTreeObserver.OnGlobalLayoutListener listener=()->apply(a);
                listeners.put(a,listener);decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            }
            public void onActivityResumed(Activity a){a.getWindow().getDecorView().post(()->apply(a));}
            public void onActivityDestroyed(Activity a){
                android.view.ViewTreeObserver.OnGlobalLayoutListener listener=listeners.remove(a);
                if(listener!=null&&a.getWindow().getDecorView().getViewTreeObserver().isAlive())
                    a.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            }
            public void onActivityStarted(Activity a){}
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,android.os.Bundle state){}
        });
    }
}
