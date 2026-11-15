package dev.weather.oneplus;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Resolves WGS84 fixes with the original public ColorOS place backend, on a caller's worker. */
public final class ColorOsPlaceResolver {
    public static final long TIMEOUT_MS = 6000L;
    private static final String HOST = "https://weather-server.allawntech.com";
    private static final String PATH = "/weather/location/for-service/sdk";
    private static final String BIZ = "weather-location-service";
    private static final String SCENE = "crypto-cipher-service";
    // SDK protocol generation, independently of this APK's version and actual package name.
    private static final String SERVICE_VERSION = "17006020";
    // Public issuer key from ColorOS's OPlus_Service_CA_E1.pem; verifies downloaded business certs.
    private static final String SERVICE_CA_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEE4ukfBjPNpkQMmrGMDxlm+5HZxn0yxJ5IKmImIsYdL8rFfRJERSNOoS9/tY7rmUW4FmIfHgQu2VDwoOnkCn/+w==";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WeatherPlaceDeadline");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, CachedPlace> CACHE = new LinkedHashMap<String, CachedPlace>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedPlace> entry) { return size() > 16; }
    };
    private static BusinessCertificate certificate;
    private static long certificateFailureUntil;
    private static final String SESSION_DEVICE_ID = encode(sha256Unchecked(random(16)));

    private ColorOsPlaceResolver() {}

    public static final class PlaceResult {
        public final String cityName, placeName, placeId, locality, adminArea, countryName;
        public final int placeType, placeDetailStatus;
        public final boolean isValid;

        private PlaceResult(JSONObject json) {
            cityName = text(json, "cityName");
            placeName = text(json, "placeName");
            placeId = text(json, "placeId");
            locality = text(json, "tertiaryName");
            adminArea = text(json, "secondaryName");
            countryName = text(json, "countryName");
            placeType = json.optInt("placeType", -1);
            placeDetailStatus = json.optInt("placeDetailStatus", -1);
            // Live service responses omit isValid; reject an explicit invalid result.
            isValid = json.optBoolean("isValid", true) && !text(json, "locationKey").isEmpty();
        }

        public boolean hasPlace() {
            return isValid && !cityName.isEmpty() && !placeId.isEmpty() && placeType >= 0
                    && placeDetailStatus == 0 && !placeName.isEmpty();
        }
    }

    private static final class CachedPlace {
        final double latitude, longitude;
        final long expires;
        final PlaceResult result;
        CachedPlace(double latitude, double longitude, long expires, PlaceResult result) {
            this.latitude = latitude; this.longitude = longitude; this.expires = expires; this.result = result;
        }
    }

    private static final class BusinessCertificate {
        final ECPublicKey key;
        final long version, expires;
        BusinessCertificate(ECPublicKey key, long version, long expires) {
            this.key = key; this.version = version; this.expires = expires;
        }
    }

    /** Caller supplies actual WGS84 coordinates; ColorOS's Google source handles conversion server-side. */
    public static PlaceResult resolve(Context context, double latitude, double longitude, String language)
            throws IOException, GeneralSecurityException {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IOException("Invalid place coordinate");
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        checkAllowed(context, deadline);
        String locale = language == null || language.trim().isEmpty() ? Locale.getDefault().toLanguageTag() : language;
        String lat = formatCoordinate(latitude), lon = formatCoordinate(longitude);
        String cacheKey = lat + ":" + lon + ":" + locale;
        synchronized (CACHE) {
            CachedPlace cached = CACHE.get(cacheKey);
            if (cached != null && canReuse(latitude, longitude, cached.latitude, cached.longitude,
                    SystemClock.elapsedRealtime(), cached.expires)) {
                if (cached.result == null) throw new IOException("Place service retry is delayed");
                return cached.result;
            }
        }
        try {
            BusinessCertificate business = getCertificate(context, deadline);
            String packageName = context.getPackageName();
            String versionCode = "1";
            try { versionCode = String.valueOf(context.getPackageManager().getPackageInfo(packageName, 0).versionCode); }
            catch (Exception ignored) { }
            long now = System.currentTimeMillis();
            String timestamp = String.valueOf(now / 1000L);
            String authCode = authCode(timestamp, packageName);
            JSONObject params = new JSONObject();
            params.put("longitude", lon); params.put("latitude", lat); params.put("language", locale);
            params.put("placeDetailLevel", "1"); params.put("source", "Google");
            params.put("brand", android.os.Build.BRAND.toLowerCase(Locale.ROOT));
            for (String field : new String[]{"mcc", "mnc", "lac", "cid", "ssid", "bssid", "sid", "nid", "bid", "udid", "oaid", "imei", "vaid"})
                params.put(field, "");
            params.put("ts", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH).format(new Date(now)));
            params.put("todayLocateCnt", 0); params.put("activationTimestamp", 0);
            params.put("attestationCertAssistContext", new JSONObject()
                    .put("attestationCertErrorVersion", "V1")
                    .put("cryptographyErrorMessage", "Hardware attestation is unavailable on this ordinary client"));
            byte[] parameterKey = random(32), parameterIv = random(16);
            for (String field : new String[]{"longitude", "latitude", "imei", "vaid", "oaid"})
                params.put(field, encode(encryptCtr(params.getString(field).getBytes(StandardCharsets.UTF_8), parameterKey, parameterIv)));
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(business.key.getParams(), RANDOM);
            KeyPair temporary = generator.generateKeyPair();
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(temporary.getPrivate()); agreement.doPhase(business.key, true);
            byte[] salt = random(32), info = BIZ.getBytes(StandardCharsets.UTF_8);
            byte[] envelopeKey = hkdf(agreement.generateSecret(), salt, info);
            byte[] wrapIv = random(12);
            Cipher wrapper = Cipher.getInstance("AES/GCM/NoPadding");
            wrapper.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(envelopeKey, "AES"), new GCMParameterSpec(128, wrapIv));
            byte[] wrapped = wrapper.doFinal((encode(parameterKey) + "%IV1%" + encode(parameterIv)).getBytes(StandardCharsets.UTF_8));
            JSONObject scene = new JSONObject().put("tmpPublicKey", encode(temporary.getPublic().getEncoded()))
                    .put("version", (now + 86400000L) * 10000L + RANDOM.nextInt(10000))
                    .put("certVersion", business.version).put("salt", encode(salt)).put("info", encode(info));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("pkgName", packageName); headers.put("timeStamp", timestamp);
            headers.put("model", android.os.Build.MODEL); headers.put("osVersion", "Android " + android.os.Build.VERSION.RELEASE);
            headers.put("versionCode", versionCode); headers.put("serviceVersionCode", SERVICE_VERSION);
            headers.put("browserVersion", "0"); headers.put("requestType", "1");
            headers.put("authType", "2"); headers.put("encryptFlag", "3"); headers.put("deviceId", SESSION_DEVICE_ID);
            headers.put("cipherInfo", new JSONObject().put(SCENE, scene).toString());
            headers.put("wrapperKey", new JSONObject().put("cipher", encode(wrapped)).put("iv", encode(wrapIv)).toString());
            JSONObject response = new JSONObject(post(context, deadline,
                    HOST + PATH + "?appId=app-weather&authCode=" + authCode, headers, params.toString()));
            if (!response.has("cityName") || response.optInt("code", 200) != 200)
                throw new IOException("Place service returned no city");
            PlaceResult result = new PlaceResult(response);
            checkAllowed(context, deadline);
            synchronized (CACHE) { CACHE.put(cacheKey, new CachedPlace(latitude, longitude,
                    SystemClock.elapsedRealtime() + 300000L, result)); }
            return result;
        } catch (JSONException error) {
            cacheFailure(cacheKey, latitude, longitude);
            throw new IOException("Invalid place service response", error);
        } catch (IOException | GeneralSecurityException error) {
            if (!Thread.currentThread().isInterrupted()) cacheFailure(cacheKey, latitude, longitude);
            throw error;
        }
    }

    static String formatCoordinate(double value) {
        return new DecimalFormat("#.0000", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }

    static String authCode(String timestamp, String packageName) throws GeneralSecurityException {
        return hex(sha256((timestamp + "app-weather" + PATH + hex(sha256(packageName.getBytes(StandardCharsets.UTF_8))))
                .getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] hkdf(byte[] secret, byte[] salt, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] extract = mac.doFinal(secret);
        mac.init(new SecretKeySpec(extract, "HmacSHA256"));
        mac.update(info); mac.update((byte) 1);
        return mac.doFinal();
    }

    static byte[] encryptCtr(byte[] plaintext, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(plaintext);
    }

    static boolean canReuse(double latitude, double longitude, double originalLatitude, double originalLongitude,
                            long now, long expires) {
        double north = Math.toRadians(latitude - originalLatitude);
        double east = Math.toRadians(longitude - originalLongitude) * Math.cos(Math.toRadians(latitude));
        return now < expires && Math.hypot(north, east) * 6371000.0 <= 5.0;
    }

    private static synchronized BusinessCertificate getCertificate(Context context, long deadline)
            throws IOException, GeneralSecurityException, JSONException {
        checkAllowed(context, deadline);
        if (certificate != null && SystemClock.elapsedRealtime() < certificate.expires) return certificate;
        if (SystemClock.elapsedRealtime() < certificateFailureUntil) throw new IOException("Place certificate retry is delayed");
        try {
            JSONObject response = new JSONObject(post(context, deadline, HOST + "/crypto/cert/upgrade",
                    new LinkedHashMap<>(), new JSONObject().put("biz", BIZ).toString()));
            if (response.optInt("code", 0) != 200) throw new IOException("Place certificate is unavailable");
            JSONObject data = response.getJSONObject("data");
            X509Certificate downloaded = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(data.getString("cert4Encrypt").getBytes(StandardCharsets.US_ASCII)));
            downloaded.checkValidity();
            PublicKey issuer = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(SERVICE_CA_KEY)));
            downloaded.verify(issuer);
            if (!(downloaded.getPublicKey() instanceof ECPublicKey) || !downloaded.getSubjectX500Principal().getName().contains("CN=" + BIZ))
                throw new GeneralSecurityException("Unexpected place business certificate");
            long remainingValidity = downloaded.getNotAfter().getTime() - System.currentTimeMillis();
            certificate = new BusinessCertificate((ECPublicKey) downloaded.getPublicKey(), data.getLong("version"),
                    SystemClock.elapsedRealtime() + Math.min(86400000L, remainingValidity));
            return certificate;
        } catch (IOException | GeneralSecurityException | JSONException error) {
            certificateFailureUntil = SystemClock.elapsedRealtime() + 30000L;
            throw error;
        }
    }

    private static String post(Context context, long deadline, String url, Map<String, String> headers, String body) throws IOException {
        checkAllowed(context, deadline);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        // Default TLS trust and hostname verification remain enabled.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout((int) Math.max(1L, Math.min(2000L, deadline - SystemClock.elapsedRealtime())));
        connection.setReadTimeout((int) Math.max(1L, Math.min(2000L, deadline - SystemClock.elapsedRealtime())));
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        for (Map.Entry<String, String> entry : headers.entrySet()) connection.setRequestProperty(entry.getKey(), entry.getValue());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        ScheduledFuture<?> close = DEADLINES.schedule(connection::disconnect,
                Math.max(1L, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        try {
            checkAllowed(context, deadline);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Place service HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) != -1) {
                    checkAllowed(context, deadline);
                    if (output.size() + count > 65536) throw new IOException("Place response is too large");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { close.cancel(false); connection.disconnect(); }
    }

    private static void checkAllowed(Context context, long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline)
            throw new IOException("Place request was cancelled or timed out");
        if (!WeatherAppearancePreferences.colorOsLocationRulesEnabled(context)
                || !context.getSharedPreferences("weather_refresh_state", Context.MODE_PRIVATE).getBoolean("network_allowed", false))
            throw new IOException("Place lookup is disabled");
    }

    private static void cacheFailure(String key, double latitude, double longitude) {
        synchronized (CACHE) { CACHE.put(key, new CachedPlace(latitude, longitude,
                SystemClock.elapsedRealtime() + 30000L, null)); }
    }

    private static String text(JSONObject json, String key) {
        Object value = json.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
    private static byte[] random(int length) { byte[] value = new byte[length]; RANDOM.nextBytes(value); return value; }
    private static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static byte[] sha256(byte[] value) throws GeneralSecurityException { return MessageDigest.getInstance("SHA-256").digest(value); }
    private static byte[] sha256Unchecked(byte[] value) {
        try { return sha256(value); } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray(), output = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) { output[i * 2] = digits[(value[i] & 255) >>> 4]; output[i * 2 + 1] = digits[value[i] & 15]; }
        return new String(output);
    }
}
