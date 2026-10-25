package dev.weather.oneplus;

/** Oplus-style 2x2 weather card; refresh and per-widget city are shared with the existing widgets. */
public final class WeatherOxygenWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return 8; }
}
