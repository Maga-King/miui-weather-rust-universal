package dev.weather.oneplus;

/** Immutable process/request identity. Cache namespace is stable; epoch fences unfinished work. */
public final class WeatherSourceSession {
    public final String sourceId, databaseName, localToken;
    public final long epoch;
    WeatherSourceSession(String sourceId,String databaseName,long epoch,String token) {
        this.sourceId=sourceId;this.databaseName=databaseName;this.epoch=epoch;localToken=token;
    }
    public boolean xiaomi(){return "xiaomi".equals(sourceId);}
    public boolean same(WeatherSourceSession other){return other!=null&&sourceId.equals(other.sourceId)&&epoch==other.epoch;}
}
