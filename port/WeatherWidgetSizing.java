package dev.weather.oneplus;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.SizeF;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Exact launcher sizes with the platform's per-RemoteViews bitmap memory budget. */
final class WeatherWidgetSizing {
    static final class Size {
        final SizeF dp;
        final int width, height;
        Size(SizeF dp, int width, int height) { this.dp=dp;this.width=width;this.height=height; }
        @Override public String toString() { return dp+":"+width+"x"+height; }
    }
    private WeatherWidgetSizing() {}

    static List<Size> resolve(Context context, Bundle options, int style) {
        LinkedHashSet<SizeF> sizes = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<SizeF> supplied = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (supplied != null) for (SizeF size:supplied) {
                if (valid(size)) sizes.add(size);
                if (sizes.size()==16) break; // Android's responsive RemoteViews limit.
            }
        }
        if (sizes.isEmpty()) {
            boolean landscape=context.getResources().getConfiguration().orientation
                    ==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int w=WeatherWidgetCatalog.defaultWidthDp(style);
            int h=WeatherWidgetCatalog.defaultHeightDp(style);
            int width=options.getInt(landscape?AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    :AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,w);
            int height=options.getInt(landscape?AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    :AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,h);
            sizes.add(new SizeF(width>0?width:w,height>0?height:h));
        }
        DisplayMetrics metrics=context.getResources().getDisplayMetrics();
        double pixels=0;
        for (SizeF size:sizes) pixels+=Math.ceil(size.getWidth()*metrics.density)
                *Math.ceil(size.getHeight()*metrics.density);
        // Android permits 1.5 screenfuls of ARGB pixels per RemoteViews. Leave
        // headroom for rounding; Binder transfers the large bitmaps via ashmem.
        double budget=Math.max(1,metrics.widthPixels*(double)metrics.heightPixels)*1.25;
        double scale=Math.min(1,Math.sqrt(budget/Math.max(1,pixels)));
        ArrayList<Size> result=new ArrayList<>();
        for (SizeF size:sizes) result.add(new Size(size,
                Math.max(1,(int)Math.floor(size.getWidth()*metrics.density*scale)),
                Math.max(1,(int)Math.floor(size.getHeight()*metrics.density*scale))));
        return result;
    }
    private static boolean valid(SizeF size) {
        return size!=null&&Float.isFinite(size.getWidth())&&Float.isFinite(size.getHeight())
                &&size.getWidth()>0&&size.getHeight()>0&&size.getWidth()<=4096&&size.getHeight()<=4096;
    }
}
