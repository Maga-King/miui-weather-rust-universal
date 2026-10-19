package dev.weather.oneplus;

/** Original sun card exposed through the standard Android widget picker. */
public final class WeatherSunWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return WeatherCardWidgetRenderer.SUN; }
}
