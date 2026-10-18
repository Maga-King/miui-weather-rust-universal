package dev.weather.oneplus;

import android.content.Context;
import android.os.CancellationSignal;

/**
 * Source must perform a real weather fetch/HTTP revalidation for the user's selected city.
 * Implement as a public no-argument class named in Application metadata
 * dev.weather.oneplus.WEATHER_REFRESH_SOURCE so it works after process death.
 * Never return networkSuccess for a cache read, redraw, HTTP error, or merely queued request.
 * Use finite network timeouts, honor cancellation, and never launch permission UI here.
 */
public interface WeatherRefreshSource {
    Result fetch(Context applicationContext, WeatherWidgetData previous, CancellationSignal cancellation) throws Exception;
    /** Optional local display data; reading it does not count as a network success. */
    default WeatherWidgetData localWidget(Context context) throws Exception { return null; }

    final class Result {
        public final WeatherWidgetData observation;
        public final boolean networkVerified, retryable;
        public final String reason;
        public org.json.JSONObject rawResponse;
        private Result(WeatherWidgetData observation, boolean verified, boolean retryable, String reason) {
            this.observation = observation; this.networkVerified = verified;
            this.retryable = retryable; this.reason = reason;
        }
        /** Only after a valid server response has completed. Observation time remains source time. */
        public static Result networkSuccess(WeatherWidgetData observation) {
            if (observation == null) throw new IllegalArgumentException("A real observation is required");
            return new Result(observation, true, false, "");
        }
        public static Result networkSuccess(WeatherWidgetData observation, org.json.JSONObject response) {
            Result result=networkSuccess(observation);
            result.rawResponse=response;
            return result;
        }
        /** Keep the last real data while awaiting city selection, consent, or source setup. */
        public static Result blocked(String reason) { return new Result(null, false, false, reason); }
        public static Result failed(String reason, boolean retryable) { return new Result(null, false, retryable, reason); }
    }
}
