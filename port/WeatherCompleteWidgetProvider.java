package dev.weather.oneplus;

/** Original complete card exposed through the standard Android widget picker. */
public final class WeatherCompleteWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return WeatherCardWidgetRenderer.COMPLETE; }
}
