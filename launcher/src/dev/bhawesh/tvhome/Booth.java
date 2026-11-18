package dev.bhawesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Booth, the owner's home-lab control server on the media server phone (homelab-control). Same
 * contract as its web app: POST /api/login {"password"} sets a "booth" session cookie; every other
 * call carries that cookie, and every POST the header X-Booth: 1. Only the cookie is kept (prefs
 * "booth"), never the password. The address defaults to 192.168.0.12:8888 and can be changed at
 * sign-in. Everything here blocks: call it off the main thread.
 */
final class Booth {
    static final String DEFAULT_HOST = "192.168.0.12:8888";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+(:[0-9]{1,5})?");
    private Booth() { }

    /** An HTTP answer other than 2xx, or a failure to reach the server (code 0). */
    static final class Failure extends IOException {
        final int code;
        Failure(int code, String message) { super(message); this.code = code; }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("booth", Context.MODE_PRIVATE); }

    static String host(Context c) { return prefs(c).getString("host", DEFAULT_HOST); }
    static boolean signedIn(Context c) { return !prefs(c).getString("cookie", "").isEmpty(); }

    /** "http://192.168.0.12/" -> "192.168.0.12:8888"; null if it can't be an address. */
    static String cleanHost(String raw) {
        String h = raw == null ? "" : raw.trim().replaceFirst("^(?i)https?://", "").replaceAll("/+$", "");
        if (h.isEmpty()) return DEFAULT_HOST;
        if (!HOST.matcher(h).matches()) return null;
        return h.contains(":") ? h : h + ":8888";
    }

    static void signOut(Context c) {
        prefs(c).edit().remove("cookie").apply();
    }

    /** Sign in; on success stores the address and the session cookie (not the password). */
    static void login(Context c, String host, String password) throws IOException {
        String body = JSONObject.quote("password") + ":" + JSONObject.quote(password);
        body = "{" + body + "}";
        HttpURLConnection h = open("http://" + host + "/api/login", "POST", true);
        try {
            send(h, body);
            int code = h.getResponseCode();
            if (code == 200) {
                String cookie = cookieOf(h);
                if (cookie == null) throw new Failure(0, "The server gave no session.");
                prefs(c).edit().putString("host", host).putString("cookie", cookie).apply();
                return;
            }
            throw new Failure(code, code == 401 ? "Wrong password." : code == 429 ? "Too many tries. Wait 10 minutes."
                    : "The server said " + code + ".");
        } finally {
            h.disconnect();
        }
    }

    static JSONObject getObject(Context c, String path) throws IOException {
        try { return new JSONObject(call(c, "GET", path, null)); } catch (org.json.JSONException e) { throw new Failure(0, "Unreadable answer."); }
    }

    static JSONArray post(Context c, String path) throws IOException {
        String r = call(c, "POST", path, "{}");
        try { return r.trim().startsWith("[") ? new JSONArray(r) : null; } catch (org.json.JSONException e) { return null; }
    }

    /** One call with the stored cookie. A 401 means the session ended: the cookie is dropped. */
    private static String call(Context c, String method, String path, String body) throws IOException {
        SharedPreferences p = prefs(c);
        String cookie = p.getString("cookie", "");
        HttpURLConnection h = open("http://" + p.getString("host", DEFAULT_HOST) + path, method, body != null);
        try {
            if (!cookie.isEmpty()) h.setRequestProperty("Cookie", cookie);
            // The server stops a service for up to ~20 s before it answers.
            if (body != null) { h.setReadTimeout(45_000); send(h, body); }
            int code = h.getResponseCode();
            if (code == 401) {
                signOut(c);
                throw new Failure(401, "Signed out. Sign in again.");
            }
            if (code / 100 != 2) throw new Failure(code, errorOf(h, code));
            return read(h.getInputStream());
        } finally {
            h.disconnect();
        }
    }

    /** A picture from the server (needs the cookie): its own disk cache through Net. */
    static android.graphics.Bitmap image(Context c, String path, int width) {
        if (path == null || path.isEmpty()) return null;
        SharedPreferences p = prefs(c);
        return Net.image(c, "http://" + p.getString("host", DEFAULT_HOST) + path, width, p.getString("cookie", ""));
    }

    private static HttpURLConnection open(String url, String method, boolean post) throws IOException {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(5000);
            h.setReadTimeout(15_000);
            h.setRequestMethod(method);
            h.setRequestProperty("Accept", "application/json");
            if (post) {
                h.setRequestProperty("X-Booth", "1");
                h.setRequestProperty("Content-Type", "application/json");
                h.setDoOutput(true);
            }
            return h;
        } catch (IOException e) {
            throw new Failure(0, "Can't reach Booth.");
        }
    }

    private static void send(HttpURLConnection h, String body) throws IOException {
        try (OutputStream out = h.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String cookieOf(HttpURLConnection h) {
        for (Map.Entry<String, List<String>> e : h.getHeaderFields().entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String v : e.getValue()) if (v.startsWith("booth=") && !v.startsWith("booth=;")) return v.split(";", 2)[0];
        }
        return null;
    }

    private static String errorOf(HttpURLConnection h, int code) {
        try (InputStream in = h.getErrorStream()) {
            if (in != null) return new JSONObject(read(in)).optString("error", "The server said " + code + ".");
        } catch (Exception ignored) { }
        return "The server said " + code + ".";
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
