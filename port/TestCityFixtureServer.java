package dev.weather.oneplus;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** One read-only fixture server per dedicated test process. No proxy or remote URL loading. */
public final class TestCityFixtureServer implements java.io.Closeable {
    public static final int PORT = 18643;
    private final ServerSocket server;
    private final JSONObject profile;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private volatile boolean closed;
    public TestCityFixtureServer(JSONObject profile) throws Exception {
        this.profile = TestCityFixtures.validate(profile);
        server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(this::acceptLoop, "TestCityHttpAccept");
        accept.setDaemon(true); accept.start();
    }
    private void acceptLoop() {
        while (!closed) try {
            Socket socket = server.accept();
            workers.execute(() -> serve(socket));
        } catch (Exception error) {
            if (!closed) android.util.Log.e("TestCity", "Fixture server stopped", error);
        }
    }
    private void serve(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(3000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String line = line(in);
            if (line == null) return;
            String[] request = line.split(" ", 3);
            if (request.length != 3) { reply(client, 400, "{}"); return; }
            int total = line.length(); String header;
            while ((header = line(in)) != null && !header.isEmpty()) {
                total += header.length();
                if (total > 16384) { reply(client, 431, "{}"); return; }
            }
            String url = request[1];
            int query = url.indexOf('?');
            String path = query < 0 ? url : url.substring(0, query);
            // Equal-length AOT replacement leaves a double slash at this boundary.
            path = path.replaceFirst("^/fixture-test/", "").replaceAll("^/+", "/");
            if (!path.startsWith("/")) path = "/" + path;
            if ("GET".equals(request[0]) && (path.equals("/wtr-v3/weather/all") || path.equals("/wtr-v3/weather/apart"))) {
                // The isolated DB contains only the synthetic city. Reject even an
                // accidentally retained ordinary key rather than masquerade as it.
                String full = "http://127.0.0.1" + path + (query < 0 ? "" : url.substring(query));
                TestCityHttpRouter.Reply routed = TestCityHttpRouter.route("GET", full, null, true, profile, System.currentTimeMillis());
                if (routed == null) reply(client, 403, "{\"error\":\"only_synthetic_city_allowed\"}");
                else reply(client, routed.status, routed.body);
            } else if (path.equals("/health")) reply(client, 200, "{\"synthetic\":true}");
            else reply(client, 403, "{\"error\":\"test_network_isolated\",\"synthetic\":true}");
        } catch (SocketTimeoutException ignored) {
        } catch (Exception error) { android.util.Log.w("TestCity", "Local fixture request failed", error); }
    }
    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int next;
        while ((next = in.read()) != -1) {
            if (next == '\n') break;
            if (next != '\r') bytes.write(next);
            if (bytes.size() > 8192) throw new IllegalArgumentException("Oversized HTTP line");
        }
        return next == -1 && bytes.size() == 0 ? null : new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
    }
    private static void reply(Socket client, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : "Forbidden";
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes); out.flush();
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (Exception ignored) {}
        workers.shutdownNow();
    }
}
