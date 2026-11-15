package dev.weather.oneplus;

import android.graphics.Canvas;

/** Changes visible card bounds inside the launcher allocation, without a bitmap resample. */
final class WeatherWidgetGeometry {
    static final int MIN=50, MAX=150;
    private WeatherWidgetGeometry() {}
    static int clamp(int percent) { return Math.max(MIN,Math.min(MAX,percent)); }
    static void apply(Canvas canvas,int width,int height,int widthPercent,int heightPercent) {
        float x=clamp(widthPercent)/100f,y=clamp(heightPercent)/100f;
        if(x==1f&&y==1f)return;
        canvas.translate(width*(1f-x)/2f,height*(1f-y)/2f);
        canvas.scale(x,y);
    }
}
