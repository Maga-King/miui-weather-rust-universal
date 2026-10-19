package dev.weather.oneplus;

import android.content.Context;
import android.graphics.Typeface;
import java.util.HashMap;
import java.util.Map;

/** Process-wide original font faces. Never retains an Activity or its Resources. */
final class WeatherWidgetFonts {
    private static final Map<Integer,Typeface> FACES = new HashMap<>();
    private static android.graphics.fonts.Font fontData;
    private WeatherWidgetFonts() {}

    static synchronized Typeface face(Context context, int axisWeight, int androidWeight) {
        int key = axisWeight * 1000 + androidWeight;
        Typeface cached = FACES.get(key);
        if (cached != null) return cached;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        // Reopening this compressed 8.8 MB font for every card pins many copies
        // in Android's font/asset caches, exhausting the shared 256 MB app heap.
        Typeface face;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                // All weight variants share one decompressed font buffer.
                if (fontData == null) fontData = new android.graphics.fonts.Font.Builder(
                        app.getAssets(), "fonts/MiSansVF.ttf").build();
                android.graphics.fonts.Font variant = new android.graphics.fonts.Font.Builder(fontData)
                        .setFontVariationSettings("'wght' " + axisWeight)
                        .setWeight(androidWeight).setSlant(0).build();
                face = new Typeface.CustomFallbackBuilder(new android.graphics.fonts.FontFamily.Builder(variant).build())
                        .setStyle(new android.graphics.fonts.FontStyle(androidWeight, 0))
                        .setSystemFallback("sans-serif").build();
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Cannot load bundled MiSansVF.ttf", error);
            }
        } else face = new Typeface.Builder(app.getAssets(), "fonts/MiSansVF.ttf")
                    .setFontVariationSettings("'wght' " + axisWeight)
                    .setWeight(androidWeight).setItalic(false).build();
        if (face == null) throw new IllegalStateException("Missing bundled MiSansVF.ttf");
        FACES.put(key, face);
        return face;
    }
}
