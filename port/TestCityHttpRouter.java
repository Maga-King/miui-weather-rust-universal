package dev.weather.oneplus;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Transport-neutral local dispatcher. It never opens a socket or forwards HTTP. */
public final class TestCityHttpRouter {
    private TestCityHttpRouter() {}
    public static final class Reply {
        public final int status;
        public final String body;
        Reply(int status, JSONObject body) { this.status = status; this.body = body.toString(); }
    }
    /**
     * null means an ordinary city; integration may use its existing real HTTP path.
     * Every reserved testcity: key returns a local reply, including unknown keys/errors.
     * Invoke BEFORE DNS/socket creation and before telemetry/subscription dispatch.
     */
    public static Reply route(String method, String url, String requestBody,
            boolean enabled, JSONObject profile, long nowMs) throws Exception {
        URI uri = new URI(url);
        Map<String, String> query = query(uri.getRawQuery());
        String key = query.get("locationKey");
        boolean reserved = TestCityFixtures.isReservedKey(key)
                || (requestBody != null && (requestBody.contains("testcity:")
                        || requestBody.toLowerCase(java.util.Locale.ROOT).contains("testcity%3a")))
                || query.values().stream().anyMatch(v -> v.contains("testcity:"));
        if (!reserved) return null;
        if (!enabled) return error(409, "test_mode_disabled");
        if (!TestCityFixtures.isTestKey(key)) return error(403, "reserved_test_city_never_uploaded");
        if (!"GET".equals(method)) return error(405, "test_city_read_only");
        String path = uri.getPath();
        if (!"/wtr-v3/weather/all".equals(path) && !"/wtr-v3/weather/apart".equals(path))
            return error(403, "test_city_endpoint_blocked");
        try { return new Reply(200, TestCityFixtures.weather(profile, nowMs)); }
        catch (JSONException invalid) { return error(422, "invalid_test_profile"); }
    }
    private static Reply error(int status, String reason) throws JSONException {
        return new Reply(status, new JSONObject().put("error", reason).put("synthetic", true));
    }
    private static Map<String, String> query(String raw) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (String part : raw.split("&")) {
            String[] entry = part.split("=", 2);
            String key = URLDecoder.decode(entry[0], "UTF-8");
            String value = entry.length == 1 ? "" : URLDecoder.decode(entry[1], "UTF-8");
            // Ambiguous keys must not let a synthetic identifier escape the gate.
            if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate query key");
            result.put(key, value);
        }
        return result;
    }
}
