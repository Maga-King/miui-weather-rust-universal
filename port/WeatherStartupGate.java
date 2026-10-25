package dev.weather.oneplus;

/** A rasterized placeholder is not the weather page. Main-thread state only. */
final class WeatherStartupGate {
    private boolean frame, content, weatherRequired, weatherFrame, scheduled, closed;
    void frame() { frame=true; }
    void content() { content=true; }
    void expectWeather() { weatherRequired=true; }
    void weatherFrame() { weatherFrame=true; }
    void ordinaryPage() { weatherRequired=false; }
    boolean ready() { return !closed&&frame&&content&&(!weatherRequired||weatherFrame); }
    boolean scheduleReveal() {
        if(scheduled||!ready())return false;
        scheduled=true;return true;
    }
    void retry() { scheduled=false; }
    boolean isClosed() { return closed; }
    void close() { closed=true; }
}
