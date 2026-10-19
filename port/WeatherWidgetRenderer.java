package dev.weather.oneplus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Static rendition of assets/maml_weather_2x2.mtz, using its original 150-unit layout. */
final class WeatherWidgetRenderer {
    private final Context context;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface medium;
    private final Typeface regular;
    private final Typeface semibold;
    private JSONObject palettes;

    WeatherWidgetRenderer(Context context) {
        this.context = context;
        // HyperOS hyper_fonts.xml: Android 400/500/800 map to MiSans axes 330/380/520.
        // The MAML numeric temperature has no explicit family; its degree uses mipro-semibold.
        regular = originalTypeface(330, 400);
        medium = originalTypeface(380, 500);
        semibold = originalTypeface(520, 800);
        try (InputStream in = context.getAssets().open("weather_widget/palettes.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            palettes = new JSONObject(bytes.toString("UTF-8"));
        } catch (Exception error) { Log.w("WeatherWidget", "Missing original palette", error); }
    }

    private Typeface originalTypeface(int axisWeight, int androidWeight) {
        return WeatherWidgetFonts.face(context, axisWeight, androidWeight);
    }

    Bitmap render(WeatherWidgetData data, long now, int width, int height) {
        // The provider budgets shared RemoteViews bitmap memory for the display.
        // Keep the host's pixel size instead of upscaling a 440 px fallback frame.
        width = Math.max(1, width);
        height = Math.max(1, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float scale = Math.min(width, height) / 150f;
        canvas.scale(scale, scale);
        float w = width / scale, h = height / scale, sy = h / 150f;
        Path clip = new Path();
        // MAML's fallback 60px corner at the original 413px canvas.
        clip.addRoundRect(new RectF(0, 0, w, h), 150f * 60f / 413f, 150f * 60f / 413f, Path.Direction.CW);
        canvas.clipPath(clip);
        int duration = duration(data, now);
        background(canvas, data, duration, w, h);
        if (data == null) {
            canvas.save();
            canvas.rotate(60, w / 2, h / 2);
            asset(canvas, "images/img_0.png", -w * .66f, -h * .66f, w * 2.32f, h * 2.32f);
            canvas.restore();
            asset(canvas, "cloud/cloud1.png", 0, 0, 180, h);
            temperature(canvas, "-", 15, 54.5f * sy, 39, 26, -4, 230);
            return bitmap;
        }
        int type = data.weatherType;
        // The original MAML places these static sunlight overlays above its gradient.
        // Other phases have no bundled light PNG; do not fabricate one for them.
        if ((type == 0 || type == 1) && duration >= 6 && duration <= 10)
            asset(canvas, "light/" + (type == 1 ? "cloudy/" : "") + duration + ".png", 0, 0, w, h);
        int cloud = type == 0 ? (duration >= 6 && duration <= 11 ? 1 : 2)
            : type >= 1 && type <= 3 ? 3
            : type >= 13 && type <= 17 || type == 22 || type == 25 ? 6 : 4;
        asset(canvas, "cloud/cloud" + cloud + ".png", 0, 0, 180, h);
        boolean rain = data.hasRain(now);
        text.setTypeface(medium); text.setTextSize(14);
        float citySize = text.measureText(data.cityName) > 105 ? 12 : 14;
        topText(canvas, data.cityName, 15, 15, citySize, 105, 255);
        temperature(canvas, data.temperature, 15, (rain ? 51.5f : 55.5f) * sy,
            rain ? 34 : 43, rain ? 25 : 27, rain ? -3 : -5, 255);
        String range = (data.high.isEmpty() ? "-" : data.high) + "°/"
            + (data.low.isEmpty() ? "-" : data.low) + "°";
        if (rain) {
            rightText(canvas, data.description, w - 15, 35, 13, 60);
            rightText(canvas, range, w - 15, 50, 13, 80);
            rainfall(canvas, data, now);
        } else {
            String detail = data.description;
            if (data.aqi >= 0) detail += (detail.isEmpty() ? "" : " ") + airQuality(data.aqi);
            topText(canvas, detail, 15, 99 * sy, 14, 120, 255);
            topText(canvas, range, 15, 119 * sy, 14, 120, 255);
        }
        if (type >= 0 && type <= 25) {
            int icon = icon(type);
            asset(canvas, "weather1/weather_" + icon + "/" + (icon <= 3 && duration < 3 ? "night" : "day") + ".png", 111, 14, 26, 26);
        }
        return bitmap;
    }

    /** Shared original sky artwork only; each card supplies its own MAML layout. */
    void drawSky(Canvas canvas, WeatherWidgetData data, long now, float width, float height) {
        int phase = duration(data, now);
        background(canvas, data, phase, width, height);
        int type = data == null ? -1 : data.weatherType;
        if ((type == 0 || type == 1) && phase >= 6 && phase <= 10)
            asset(canvas, "light/" + (type == 1 ? "cloudy/" : "") + phase + ".png", 0, 0, width, height);
        int cloud = type == 0 ? (phase >= 6 && phase <= 11 ? 1 : 2)
            : type >= 1 && type <= 3 ? 3
            : type >= 13 && type <= 17 || type == 22 || type == 25 ? 6 : 4;
        asset(canvas, "cloud/cloud" + cloud + ".png", 0, 0, width * 1.2f, height);
    }

    private void background(Canvas canvas, WeatherWidgetData data, int duration, float w, float h) {
        int[] colors = {0xff254da8, 0xff4271d6, 0xff588bf5, 0xff88c4fc, 0xffbfe0e3};
        float[] stops = {0, .38f, .57f, .87f, 1};
        if (data != null && palettes != null && data.weatherType >= 0 && data.weatherType <= 25) {
            try {
                int group = backgroundType(data.weatherType);
                String prefix = group == 0 ? "clear" : group == 1 ? "cloudy" : "gradient";
                String suffix = group <= 1 ? "new" : duration < 5 ? "night" : "day";
                int index = group <= 1 ? duration : group;
                String[] names = {"top", "mid1", "mid2", "mid3", "bottom"};
                for (int i = 0; i < 5; i++)
                    colors[i] = Color.parseColor(palettes.getJSONArray(prefix + "_" + names[i] + "_colors_" + suffix).getString(index));
                for (int i = 1; i < 4; i++)
                    stops[i] = (float) palettes.getJSONArray(prefix + "_mid" + i + "_poses_" + suffix).getDouble(index);
            } catch (Exception error) { Log.w("WeatherWidget", "Cannot read original palette", error); }
        }
        // gradient_y1 in the original design extends clear/cloudy skies past the card.
        float gradientHeight = data != null && (data.weatherType == 0 || data.weatherType == 1) ? h * 1.14f : h;
        paint.setShader(new LinearGradient(w / 2, 0, w / 2, gradientHeight, colors, stops, Shader.TileMode.CLAMP));
        paint.setAlpha(255); canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
    }
    private void asset(Canvas canvas, String path, float x, float y, float w, float h) {
        try (InputStream in = context.getAssets().open("weather_widget/" + path)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) {
                paint.setAlpha(255);
                canvas.drawBitmap(bitmap, null, new RectF(x, y, x + w, y + h), paint);
                bitmap.recycle();
            }
        } catch (Exception error) { Log.w("WeatherWidget", "Missing original asset " + path); }
    }
    private void temperature(Canvas canvas, String value, float x, float y, float size,
                             float degreeSize, float offset, int alpha) {
        setup(size, regular, alpha);
        float width = text.measureText(value);
        canvas.drawText(value, x, y - (text.ascent() + text.descent()) / 2, text);
        setup(degreeSize, semibold, 255);
        canvas.drawText("°", x + width, y + offset - (text.ascent() + text.descent()) / 2, text);
    }
    private void setup(float size, Typeface face, int alpha) {
        text.setColor(Color.WHITE); text.setAlpha(alpha); text.setTypeface(face);
        text.setTextSize(size); text.setTextAlign(Paint.Align.LEFT);
    }
    private void topText(Canvas canvas, String value, float x, float y, float size, float width, int alpha) {
        setup(size, medium, alpha);
        canvas.drawText(TextUtils.ellipsize(value, text, width, TextUtils.TruncateAt.END).toString(), x, y - text.ascent(), text);
    }
    private void rightText(Canvas canvas, String value, float x, float y, float size, float width) {
        setup(size, medium, 255);
        String fit = TextUtils.ellipsize(value, text, width, TextUtils.TruncateAt.END).toString();
        canvas.drawText(fit, x - text.measureText(fit), y - text.ascent(), text);
    }
    private void rainfall(Canvas canvas, WeatherWidgetData data, long now) {
        topText(canvas, data.rainDescription, 17, 74, 12, 120, 255);
        double[] values = data.rainfall();
        paint.setColor(Color.WHITE); paint.setStrokeWidth(3); paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 20; i++) {
            float x = 17.55f + i * 6.1f;
            paint.setAlpha(61); canvas.drawLine(x, 94, x, 120, paint);
            double v = Math.min(1, values[i]);
            float bar = (float) (v <= 0 ? 0 : v <= .25 ? Math.max(3, v / .25 * (26d / 3 - 3) + 3)
                : v <= .35 ? 26d / 3 + (v - .25) / .1 * 26d / 3
                : 52d / 3 + (v - .35) / .65 * 26d / 3);
            if (bar > 0) { paint.setAlpha(255); canvas.drawLine(x, 120 - bar, x, 120, paint); }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
        if (!data.zoneId.isEmpty()) format.setTimeZone(TimeZone.getTimeZone(data.zoneId));
        // Cached forecast labels stay anchored to the forecast interval, never a fake new 'now'.
        topText(canvas, format.format(data.rainStartMs), 17, 124, 9, 30, 153);
        String middle = format.format(data.rainStartMs + 3600000);
        setup(9, medium, 153); canvas.drawText(middle, 75 - text.measureText(middle) / 2, 124 - text.ascent(), text);
        String end = format.format(data.rainStartMs + 7200000);
        canvas.drawText(end, 135 - text.measureText(end), 124 - text.ascent(), text);
    }
    private String airQuality(int aqi) {
        String key = aqi > 300 ? "hazardous" : aqi > 200 ? "dangerous" : aqi > 150 ? "unhealthy"
            : aqi > 100 ? "pollution" : aqi > 50 ? "moderate" : "good";
        int id = context.getResources().getIdentifier("weather_widget_aqi_" + key, "string", context.getPackageName());
        return id == 0 ? "" : context.getString(id);
    }
    static int icon(int t) {
        if (t == 5 || t == 6 || t == 9) return 4;
        if (t == 8) return 11;
        if (t == 13 || t == 15) return 15;
        if (t == 16) return 13;
        if (t == 14 || t == 17) return 17;
        if (t == 20 || t == 21 || t == 19) return 18;
        if (t == 23) return 20;
        return t;
    }
    static int backgroundType(int t) {
        if (t == 2) return 4;
        if (t == 3) return 5;
        if (t >= 4 && t <= 12) return 6;
        if (t >= 13 && t <= 17) return 7;
        if (t == 20 || t == 21) return 8;
        if (t == 18 || t == 19) return 9;
        if (t == 23 || t == 24) return 10;
        if (t == 22 || t == 25) return 11;
        return t;
    }
    static int duration(WeatherWidgetData data, long now) {
        Calendar calendar = Calendar.getInstance(data != null && !data.zoneId.isEmpty()
            ? TimeZone.getTimeZone(data.zoneId) : TimeZone.getDefault());
        calendar.setTimeInMillis(now);
        double minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        // Unknown sunrise/sunset only affects decorative appearance, never weather values.
        double rise = data != null && data.sunriseMinute >= 0 ? data.sunriseMinute : 360;
        double set = data != null && data.sunsetMinute > rise ? data.sunsetMinute : 1080;
        double day12 = (set - rise) / 12, day6 = (set - rise) / 6;
        double night12 = (1440 - set + rise) / 12, night36 = (1440 - set + rise) / 36;
        double[] bounds = {rise - 3 * night12, rise - 2 * night12, rise - night12, rise,
            rise + day12, rise + 2 * day12, rise + 2 * day12 + day6,
            rise + 2 * day12 + 2 * day6, rise + 2 * day12 + 3 * day6,
            rise + 2 * day12 + 4 * day6, set - day12, set,
            set + night36, set + 2 * night36, set + 3 * night36, set + 3 * night36 + night12};
        for (int i = 0; i < bounds.length - 1; i++) if (minute >= bounds[i] && minute < bounds[i + 1]) return i + 1;
        return 0;
    }
}
