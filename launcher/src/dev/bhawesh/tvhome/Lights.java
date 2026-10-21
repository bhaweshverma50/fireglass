package dev.bhawesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WiZ bulbs on the same Wi-Fi, driven with their local protocol: JSON datagrams on UDP 38899
 * (getPilot / setPilot), the one the WiZ app and Lumen use. No cloud. Bulbs don't all answer a
 * broadcast, so they're found by asking every address on the /24, and remembered.
 *
 * Some routers keep the TV and the bulbs apart (the TV can't even resolve a bulb's address). Then
 * the lights go through Lumen, the owner's WiZ server on the LAN, if one answers: found at
 * lumen.lan or by asking every address on port 3000, and remembered.
 *
 * Two automatic modes (Customise home, or the phone): dim while something plays, and take the
 * colour of the app focused on the home. Before the first automatic change the lights are
 * snapshotted, and restored when no mode applies any more. Changing the lights by hand meanwhile
 * drops the snapshot and holds the modes off until something changes (play, stop, home, focus):
 * what you chose stays.
 */
final class Lights {
    private static final String TAG = "TvHome";
    private static final int PORT = 38899;
    static final String DIM = "lights_dim", MATCH = "lights_match"; // HomeSettings keys

    /** WiZ's built-in scenes: {id, name}. */
    static final Object[][] SCENES = {
            {6, "Cozy"}, {11, "Warm white"}, {12, "Daylight"}, {16, "Relax"}, {18, "TV time"},
            {29, "Candlelight"}, {3, "Sunset"}, {1, "Ocean"}, {4, "Party"}, {14, "Night light"},
            {15, "Focus"}, {5, "Fireplace"}, {2, "Romance"}, {7, "Forest"}, {8, "Pastel colours"},
            {9, "Wake up"}, {10, "Bedtime"}, {13, "Cool white"}, {17, "True colours"}, {20, "Spring"},
            {21, "Summer"}, {22, "Fall"}, {23, "Deep dive"}, {24, "Jungle"}, {25, "Mojito"},
            {26, "Club"}, {27, "Christmas"}, {28, "Halloween"}, {30, "Golden white"}, {31, "Pulse"},
            {32, "Steampunk"}, {19, "Plant growth"},
    };

    // Automatic changes and the TV panel go through one thread, so they reach the bulb in order.
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private Lights() { }

    // ---------------------------------------------------------------- talking to bulbs

    private static JSONObject ask(String ip, JSONObject msg, int timeoutMs) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            byte[] out = msg.toString().getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(out, out.length, InetAddress.getByName(ip), PORT));
            byte[] buf = new byte[1024];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            s.receive(in);
            return new JSONObject(new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject pilot(String ip) {
        try {
            JSONObject r = ask(ip, new JSONObject().put("method", "getPilot").put("params", new JSONObject()), 700);
            return r == null ? null : r.optJSONObject("result");
        } catch (JSONException e) {
            return null;
        }
    }

    private static void setPilot(String ip, JSONObject params) {
        try {
            // setState for off; setPilot otherwise (it also switches the bulb on).
            JSONObject msg = params.has("state") && !params.optBoolean("state")
                    ? new JSONObject().put("method", "setState").put("params", new JSONObject().put("state", false))
                    : new JSONObject().put("method", "setPilot").put("params", params);
            // Bulbs drop a datagram now and then; one retry if no ack.
            if (ask(ip, msg, 500) == null) ask(ip, msg, 500);
        } catch (JSONException ignored) { }
    }

    // ---------------------------------------------------------------- the API's shape

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lights", Context.MODE_PRIVATE);
    }

    static Set<String> known(Context c) {
        return new HashSet<>(prefs(c).getStringSet("bulbs", new HashSet<>()));
    }

    /** {bulbs, scenes, auto}: docs/remote-api.md. */
    static JSONObject status(Context c) throws JSONException {
        JSONArray bulbs = new JSONArray();
        for (JSONObject raw : raw(c)) bulbs.put(bulb(raw.getString("ip"), raw.optJSONObject("pilot")));
        JSONArray scenes = new JSONArray();
        for (Object[] s : SCENES) scenes.put(new JSONObject().put("id", s[0]).put("name", s[1]));
        JSONObject auto = new JSONObject().put("dimWhilePlaying", HomeSettings.get(c, DIM) == 1)
                .put("matchHome", HomeSettings.get(c, MATCH) == 1);
        return new JSONObject().put("bulbs", bulbs).put("scenes", scenes).put("auto", auto)
                .put("via", !known(c).isEmpty() ? "direct" : lumen(c) != null ? "lumen" : JSONObject.NULL);
    }

    private static JSONObject bulb(String ip, JSONObject p) throws JSONException {
        JSONObject b = new JSONObject().put("ip", ip).put("online", p != null);
        if (p == null) {
            for (String k : new String[]{"mac", "on", "dimming", "temp", "rgb", "scene"}) b.put(k, JSONObject.NULL);
            return b;
        }
        b.put("mac", p.optString("mac")).put("on", p.optBoolean("state"))
                .put("dimming", p.optInt("dimming", 100));
        b.put("temp", p.has("temp") ? p.optInt("temp") : JSONObject.NULL);
        b.put("rgb", p.has("r") ? new JSONArray().put(p.optInt("r")).put(p.optInt("g")).put(p.optInt("b")) : JSONObject.NULL);
        int scene = p.optInt("sceneId", 0);
        // Warm white, daylight and the like report a scene too; temp/rgb say more for those.
        b.put("scene", scene > 0 && !p.has("temp") && !p.has("r") ? scene : JSONObject.NULL);
        return b;
    }

    /** From the phone or the TV panel: {on, dimming, temp, rgb, scene}, any of them, to every bulb. */
    static JSONObject set(Context c, JSONObject p) throws JSONException {
        JSONObject params = params(p);
        manual();
        write(c, params);
        return status(c);
    }

    private static JSONObject params(JSONObject p) throws JSONException {
        JSONObject o = new JSONObject();
        if (p.has("on")) o.put("state", p.getBoolean("on"));
        if (p.has("dimming")) o.put("dimming", Math.max(10, Math.min(100, p.getInt("dimming"))));
        if (p.has("temp")) o.put("temp", Math.max(2200, Math.min(6500, p.getInt("temp"))));
        JSONArray rgb = p.optJSONArray("rgb");
        if (rgb != null && rgb.length() == 3) o.put("r", rgb.getInt(0)).put("g", rgb.getInt(1)).put("b", rgb.getInt(2));
        if (p.has("scene") && !p.isNull("scene")) o.put("sceneId", p.getInt("scene"));
        return o;
    }

    /** Ask every address on the /24 for its pilot; whatever answers is a bulb. About 2.5 s. */
    static JSONObject discover(Context c) throws JSONException {
        Set<String> found = new HashSet<>();
        String base = subnet(c);
        if (base != null) {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] out = "{\"method\":\"getPilot\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
                // Twice: the first pass mostly sets off ARP lookups (packets to an address not yet
                // resolved are queued and, in a burst this size, dropped); the second gets through.
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 1; i < 255; i++) {
                        // An address with no one behind it can fail the send; that mustn't end the sweep.
                        try { s.send(new DatagramPacket(out, out.length, InetAddress.getByName(base + i), PORT)); }
                        catch (Exception ignored) { }
                        if (i % 32 == 0) Thread.sleep(15); // don't overrun the socket's buffer
                    }
                    if (pass == 0) Thread.sleep(600);
                }
                s.setSoTimeout(250);
                long until = System.currentTimeMillis() + 2500;
                byte[] buf = new byte[1024];
                while (System.currentTimeMillis() < until) {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(in);
                        String text = new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8);
                        if (text.contains("\"getPilot\"")) found.add(in.getAddress().getHostAddress());
                    } catch (SocketTimeoutException ignored) { }
                }
            } catch (Exception e) {
                Log.w(TAG, "lights sweep: " + e);
            }
        }
        // A sweep that finds nothing keeps what was known (the bulb may just be switched off at the wall).
        if (!found.isEmpty()) prefs(c).edit().putStringSet("bulbs", found).apply();
        else if (known(c).isEmpty()) findLumen(c, base);
        Log.i(TAG, "lights: " + found + (found.isEmpty() ? ", lumen " + lumen(c) : ""));
        return status(c);
    }

    private static String subnet(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ip = wm == null || wm.getConnectionInfo() == null ? 0 : wm.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + ".";
    }

    // ---------------------------------------------------------------- direct, or through Lumen

    /** Something to control, one way or the other. */
    static boolean available(Context c) {
        return !known(c).isEmpty() || lumen(c) != null;
    }

    /** Each bulb as {ip, pilot}: pilot is getPilot's result, or absent when it didn't answer. */
    private static java.util.List<JSONObject> raw(Context c) throws JSONException {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        if (!known(c).isEmpty()) {
            for (String ip : known(c)) out.add(new JSONObject().put("ip", ip).putOpt("pilot", pilot(ip)));
            return out;
        }
        String lumen = lumen(c);
        JSONObject st = lumen == null ? null : http("GET", lumen + "/api/status", null, 4000);
        JSONArray bulbs = st == null ? null : st.optJSONArray("bulbs");
        for (int i = 0; bulbs != null && i < bulbs.length(); i++) {
            JSONObject b = bulbs.getJSONObject(i);
            JSONObject pilot = b.optBoolean("online") ? b.optJSONObject("pilot") : null;
            if (pilot != null && !pilot.has("mac")) pilot.put("mac", b.optString("mac"));
            out.add(new JSONObject().put("ip", b.optString("ip")).putOpt("pilot", pilot));
        }
        return out;
    }

    /** The first bulb that answers, as getPilot's result. */
    private static JSONObject read(Context c) throws JSONException {
        for (JSONObject r : raw(c)) if (r.has("pilot")) return r.getJSONObject("pilot");
        return null;
    }

    /** setPilot params (state, dimming, temp, r/g/b, sceneId) to every bulb. */
    private static void write(Context c, JSONObject params) throws JSONException {
        if (!known(c).isEmpty()) {
            for (String ip : known(c)) setPilot(ip, params);
            return;
        }
        String lumen = lumen(c);
        if (lumen == null) return;
        JSONObject light = new JSONObject(params.toString());
        light.remove("state");
        // Lumen's pilot call switches the bulb on with the change; power alone is its own call.
        if (light.length() == 0 || (params.has("state") && !params.optBoolean("state"))) {
            http("POST", lumen + "/api/power", new JSONObject().put("ip", "all").put("state", params.optBoolean("state", true)), 4000);
        } else {
            http("POST", lumen + "/api/pilot", new JSONObject().put("ip", "all").put("params", light), 4000);
        }
    }

    static String lumen(Context c) {
        return prefs(c).getString("lumen", null);
    }

    /** lumen.lan first, then port 3000 on every address, 32 at a time; whatever says it has bulbs. */
    private static void findLumen(Context c, String base) {
        java.util.List<String> tries = new java.util.ArrayList<>();
        tries.add("http://lumen.lan:3000");
        if (base != null) for (int i = 1; i < 255; i++) tries.add("http://" + base + i + ":3000");
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(32);
        java.util.concurrent.atomic.AtomicReference<String> hit = new java.util.concurrent.atomic.AtomicReference<>();
        for (String url : tries) pool.execute(() -> {
            if (hit.get() != null) return;
            JSONObject st = http("GET", url + "/api/status", null, 700);
            if (st != null && st.has("bulbs") && st.has("effects")) hit.compareAndSet(null, url);
        });
        pool.shutdown();
        try { pool.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        pool.shutdownNow();
        if (hit.get() != null) prefs(c).edit().putString("lumen", hit.get()).apply();
    }

    private static JSONObject http(String method, String url, JSONObject body, int timeoutMs) {
        java.net.HttpURLConnection h = null;
        try {
            h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            h.setConnectTimeout(timeoutMs);
            h.setReadTimeout(timeoutMs);
            h.setRequestMethod(method);
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            if (h.getResponseCode() >= 400) return null;
            try (java.io.InputStream in = h.getInputStream()) { return new JSONObject(Catalog.readAll(in)); }
        } catch (Exception e) {
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    static JSONObject setAuto(Context c, JSONObject p) throws JSONException {
        SharedPreferences.Editor e = HomeSettings.prefs(c).edit();
        if (p.has("dimWhilePlaying")) e.putInt(DIM, p.getBoolean("dimWhilePlaying") ? 1 : 0);
        if (p.has("matchHome")) e.putInt(MATCH, p.getBoolean("matchHome") ? 1 : 0);
        e.putInt("settings_version", HomeSettings.version(c) + 1).apply();
        main.post(() -> apply(c.getApplicationContext()));
        return status(c);
    }

    // ---------------------------------------------------------------- automatic

    // Main thread only.
    private static boolean atHome, playing, held;
    private static int homeColor;
    // The lights as they were before the automatic modes. Only touched on the io thread, so a
    // restore always runs after the snapshot it needs, however slow reading the bulb is.
    private static JSONObject snapshot;
    private static String applied = "";     // what the modes last asked for, so repeats are skipped
    private static final Runnable settle = () -> apply(null);
    private static Context app;

    static void atHome(Context c, boolean home) {
        app = c.getApplicationContext();
        if (home != atHome) held = false;
        atHome = home;
        main.removeCallbacks(settle);
        main.postDelayed(settle, home ? 300 : 600);
    }

    /** The colour of what's focused on the home; applied once focus has rested a moment. */
    static void homeColor(Context c, int color) {
        app = c.getApplicationContext();
        if (color != homeColor) held = false;
        homeColor = color;
        main.removeCallbacks(settle);
        main.postDelayed(settle, 450);
    }

    /** From Watching: whether any app's media is playing. Pauses shorter than a few seconds don't count. */
    static void playing(Context c, boolean now) {
        app = c.getApplicationContext();
        if (now == playing) return;
        playing = now;
        held = false;
        main.removeCallbacks(settle);
        main.postDelayed(settle, now ? 2500 : 4000);
    }

    /** A hand-made change: forget the snapshot so leaving a mode doesn't undo it. */
    private static void manual() {
        main.post(() -> { applied = "manual"; held = true; });
        io.execute(() -> snapshot = null);
    }

    private static void apply(Context given) {
        Context c = given != null ? given : app;
        if (c == null || held || !available(c)) return;
        boolean dim = playing && HomeSettings.get(c, DIM) == 1;
        boolean match = !dim && atHome && HomeSettings.get(c, MATCH) == 1 && homeColor != 0;
        JSONObject target = null;
        String key;
        try {
            if (dim) {
                // Low and warm: enough to see the room, not the screen's competition.
                target = new JSONObject().put("state", true).put("dimming", 15).put("temp", 2200);
                key = "dim";
            } else if (match) {
                // The app's colour, lifted toward white so it lights a room rather than floods it.
                int col = mix(homeColor, Color.WHITE, 0.2f);
                target = new JSONObject().put("state", true).put("dimming", 45)
                        .put("r", Color.red(col)).put("g", Color.green(col)).put("b", Color.blue(col));
                key = "match:" + col;
            } else {
                key = "rest";
            }
        } catch (JSONException e) {
            return;
        }
        if (key.equals(applied)) return;
        boolean wasAuto = applied.equals("dim") || applied.startsWith("match:");
        applied = key;
        JSONObject t = target;
        io.execute(() -> {
            try {
                if (t != null) {
                    if (snapshot == null) {
                        JSONObject before = read(c);
                        snapshot = before == null ? null : restorable(before);
                    }
                    write(c, t);
                } else {
                    if (wasAuto && snapshot != null) write(c, snapshot);
                    snapshot = null;
                }
            } catch (JSONException ignored) { }
        });
    }

    /** A getPilot result as the setPilot that brings it back. */
    private static JSONObject restorable(JSONObject p) {
        try {
            if (!p.optBoolean("state")) return new JSONObject().put("state", false);
            JSONObject o = new JSONObject().put("state", true).put("dimming", p.optInt("dimming", 100));
            if (p.has("r")) o.put("r", p.optInt("r")).put("g", p.optInt("g")).put("b", p.optInt("b"));
            else if (p.has("temp")) o.put("temp", p.optInt("temp"));
            else if (p.optInt("sceneId") > 0) o.put("sceneId", p.optInt("sceneId"));
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Off the main thread: for the TV panel, which reads and writes like the phone does. */
    static void async(Context c, Task t, Done d) {
        io.execute(() -> {
            JSONObject r = null;
            try { r = t.run(); } catch (Exception e) { Log.w(TAG, "lights: " + e); }
            JSONObject res = r;
            main.post(() -> d.done(res));
        });
    }

    interface Task { JSONObject run() throws Exception; }
    interface Done { void done(JSONObject status); }
}
