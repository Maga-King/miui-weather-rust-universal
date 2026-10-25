package dev.weather.oneplus;

/** Stable style order shared by rendering, instance settings and city refresh. */
final class WeatherWidgetCatalog {
    private WeatherWidgetCatalog() {}
    static final int OXYGEN_FIRST = 8;
    static final Class<?>[] PROVIDERS = {
        WeatherWidgetProvider.class, WeatherDualCityWidgetProvider.class,
        WeatherHourlyWidgetProvider.class, WeatherSmallWidgetProvider.class,
        WeatherCustomWidgetProvider.class, WeatherDailyWidgetProvider.class,
        WeatherCompleteWidgetProvider.class, WeatherSunWidgetProvider.class,
        WeatherOxygenWidgetProvider.class,
        WeatherOxygenQuickSmallWidgetProvider.class,
        WeatherOxygenQuickMediumWidgetProvider.class,
        WeatherOxygenQuickLargeWidgetProvider.class,
        WeatherOxygenDragonflyWidgetProvider.class,
        WeatherOxygenSeedlingStripWidgetProvider.class,
        WeatherOxygenSeedlingSquareWidgetProvider.class,
        WeatherOxygenGlassStripWidgetProvider.class,
        WeatherOxygenAdaptiveWidgetProvider.class,
        WeatherOxygenTemperatureWidgetProvider.class,
        WeatherOxygenCurrentWidgetProvider.class,
        WeatherOxygenUvWidgetProvider.class,
        WeatherOxygenSunriseWidgetProvider.class,
        WeatherOxygenRpkMicroWidgetProvider.class,
        WeatherOxygenRpkStripWidgetProvider.class,
        WeatherOxygenRpkTodayWidgetProvider.class,
        WeatherOxygenQuickSmallOosWidgetProvider.class
    };
    static boolean isOxygen(int style) {
        return style >= OXYGEN_FIRST && style < PROVIDERS.length;
    }
    static int defaultWidthDp(int style) {
        if (style == 1 || style == 2 || style == 5 || style == 6 || style == 10 || style == 11 || style == 12) return 340;
        if (style >= 17 && style <= 21) return 64;
        return 150;
    }
    static int defaultHeightDp(int style) {
        if (style == 1 || style == 3 || style == 13 || style == 15 || style == 22) return 60;
        if (style == 6 || style == 11 || style == 12) return 340;
        if (style >= 17 && style <= 21) return 64;
        return 150;
    }
}
