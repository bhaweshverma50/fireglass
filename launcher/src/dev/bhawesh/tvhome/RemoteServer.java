package dev.bhawesh.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone app's API (docs/remote-api.md): a small HTTP/1.1 server on the LAN, advertised over
 * mDNS, paired with a PIN shown on the TV. Lives in HomeService, which is always running.
 */
final class RemoteServer {
    static final int PORT = 8090;
    private static final String TAG = "TvHome";

    private final HomeService svc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Map<String, byte[]> banners = new HashMap<>();
    private final Map<String, Integer> colors = new HashMap<>();
    private ServerSocket server;
    private NsdManager.RegistrationListener nsd;
    private volatile String pin;
    private volatile long pinUntil;
    // A 4-digit PIN is 10,000 guesses: without a cap anyone on the LAN can try them all in its 2 min.
    // A PIN takes 3 wrong guesses. New PINs wait 30 s after the last request or wrong guess, doubled
    // for every 3 wrong guesses up to an hour; counted across PINs, so asking for fresh ones doesn't
    // reset it. A successful pairing clears it.
    private static final int PIN_TRIES = 3;
    private static final long PIN_COOLDOWN_MS = 30_000, PIN_COOLDOWN_MAX_MS = 3_600_000;
    private final Object pairing = new Object();
    private int pinTriesLeft, misses;
    private long nextPinAt;

    RemoteServer(HomeService svc) { this.svc = svc; }

    void start() {
        Thread t = new Thread(this::acceptLoop, "remote-http");
        t.setDaemon(true);
        t.start();
        advertise();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (nsd != null) {
            try { ((NsdManager) svc.getSystemService(Context.NSD_SERVICE)).unregisterService(nsd); }
            catch (RuntimeException ignored) { }
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket(PORT);
            while (!server.isClosed()) {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (IOException e) {
            Log.w(TAG, "remote server stopped: " + e);
        }
    }

    private void advertise() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(tvName());
        info.setServiceType("_tvhome._tcp.");
        info.setPort(PORT);
        info.setAttribute("v", "1");
        nsd = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "mdns: " + i.getServiceName()); }
            @Override public void onRegistrationFailed(NsdServiceInfo i, int err) { Log.w(TAG, "mdns failed " + err); }
            @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int err) { }
        };
        ((NsdManager) svc.getSystemService(Context.NSD_SERVICE))
                .registerService(info, NsdManager.PROTOCOL_DNS_SD, nsd);
    }

    // ---------------------------------------------------------------- http

    static final class Req {
        String method, path, query = "", auth;
        byte[] body = new byte[0];
        // An upload's body is left on the socket for the handler to stream (see parse).
        InputStream stream;
        int length;
        JSONObject json() throws JSONException {
            return new JSONObject(body.length == 0 ? "{}" : new String(body, StandardCharsets.UTF_8));
        }
        String q(String key) {
            for (String kv : query.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0 && kv.substring(0, i).equals(key)) {
                    try { return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); }
                    catch (java.io.UnsupportedEncodingException e) { return null; }
                }
            }
            return null;
        }
    }

    static final class Res {
        int code = 200;
        String type = "application/json; charset=utf-8";
        byte[] body;
        static Res json(int code, Object o) {
            Res r = new Res();
            r.code = code;
            r.body = o.toString().getBytes(StandardCharsets.UTF_8);
            return r;
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            Req req = parse(in);
            Res res;
            if (req == null) return;
            try {
                res = route(req);
            } catch (Exception e) {
                Log.w(TAG, "api " + req.path, e);
                res = Res.json(500, err(e.getClass().getSimpleName()));
            }
            write(sock.getOutputStream(), res);
        } catch (IOException ignored) { }
    }

    private static Req parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0];
        String target = parts[1];
        int qi = target.indexOf('?');
        r.path = qi < 0 ? target : target.substring(0, qi);
        if (qi >= 0) r.query = target.substring(qi + 1);
        int length = 0;
        // Pictures from the phone stream to disk (up to 25 MB); everything else is small JSON.
        boolean upload = r.method.equals("POST") && r.path.equals("/api/wallpapers");
        for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
            int c = h.indexOf(':');
            if (c < 0) continue;
            String name = h.substring(0, c).trim().toLowerCase(Locale.ROOT), value = h.substring(c + 1).trim();
            if (name.equals("content-length")) length = Math.min(Integer.parseInt(value), upload ? 25 << 20 : 64 * 1024);
            else if (name.equals("authorization") && value.startsWith("Bearer ")) r.auth = value.substring(7);
        }
        if (upload) { r.stream = in; r.length = length; return r; }
        r.body = new byte[length];
        for (int got = 0; got < length; ) {
            int n = in.read(r.body, got, length - got);
            if (n < 0) break;
            got += n;
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        return b.toString("UTF-8");
    }

    private static void write(OutputStream out, Res r) throws IOException {
        String head = "HTTP/1.1 " + r.code + " " + (r.code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + r.type + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(r.body);
        out.flush();
    }

    // ---------------------------------------------------------------- routes

    private Res route(Req r) throws Exception {
        String m = r.method, p = r.path;
        if (m.equals("POST") && p.equals("/api/pair/request")) return pairRequest(r);
        if (m.equals("POST") && p.equals("/api/pair/confirm")) return pairConfirm(r);
        if (r.auth == null || !tokens().contains(r.auth)) return Res.json(401, err("unpaired"));
        // Lights are for the room, not the screen: adjusting them leaves a sleeping TV asleep.
        if (m.equals("POST") && !p.startsWith("/api/lights")) wake();

        if (m.equals("GET") && p.equals("/api/info")) {
            return Res.json(200, new JSONObject().put("name", tvName()).put("model", Build.MODEL)
                    .put("version", "1").put("keys", Keys.status(svc)));
        }
        if (m.equals("GET") && p.equals("/api/keys/bootstrap")) {
            return Res.json(200, new JSONObject().put("adb_port", 5555).put("cmd", Keys.bootstrapCommand(svc)));
        }
        if (m.equals("GET") && p.equals("/api/now")) {
            String pkg = svc.front();
            // Nothing seen since the service (re)started: the home screen is what's up.
            if (pkg == null || pkg.isEmpty()) pkg = svc.getPackageName();
            AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
            return Res.json(200, new JSONObject().put("pkg", pkg).put("label", label(pkg))
                    .put("playing", am != null && am.isMusicActive()));
        }
        if (m.equals("GET") && p.equals("/api/apps")) return Res.json(200, apps());
        if (m.equals("GET") && p.startsWith("/api/apps/") && p.endsWith("/banner.png")) {
            byte[] png = banner(p.substring("/api/apps/".length(), p.length() - "/banner.png".length()));
            if (png == null) return Res.json(404, err("no_app"));
            Res res = new Res();
            res.type = "image/png";
            res.body = png;
            return res;
        }
        if (p.startsWith("/api/wallpapers")) return wallpapers(r);
        if (p.startsWith("/api/lights")) return lights(r);
        if (m.equals("POST") && p.equals("/api/key")) {
            JSONObject b = r.json();
            String key = b.optString("key");
            if (key.equals("home")) { svc.goHome(); return ok(); }
            Integer code = KEYCODES.get(key);
            if (code == null) return Res.json(400, err("unknown_key"));
            return keyCmd("key " + code + (b.optBoolean("long") ? " 1" : " 0"));
        }
        if (m.equals("POST") && p.equals("/api/text")) {
            JSONObject b = r.json();
            String text = b.optString("text").replace("\n", " ");
            if (!text.isEmpty()) {
                Res r1 = keyCmd("text " + text);
                if (r1.code != 200) return r1;
            }
            return b.optBoolean("submit") ? keyCmd("key 66 0") : ok();
        }
        if (m.equals("POST") && p.equals("/api/launch")) {
            return launch(r.json().optString("pkg")) ? ok() : Res.json(404, err("no_app"));
        }
        if (m.equals("POST") && p.equals("/api/open")) {
            JSONObject b = r.json();
            return open(b.optString("url"), b.optString("pkg", null)) ? ok() : Res.json(400, err("cannot_open"));
        }
        if (p.equals("/api/search") || p.equals("/api/recs") || p.equals("/api/recent") || p.equals("/api/play")) {
            return Catalog.handle(svc, this, r.method, p, r.q("q"), r.json());
        }
        return Res.json(404, err("not_found"));
    }

    private Res lights(Req r) throws Exception {
        String m = r.method, p = r.path;
        if (m.equals("GET") && p.equals("/api/lights")) {
            // Nothing known yet: look on the first ask (about 2.5 s), then answer.
            return Res.json(200, !Lights.available(svc) ? Lights.discover(svc) : Lights.status(svc));
        }
        if (m.equals("POST") && p.equals("/api/lights/discover")) return Res.json(200, Lights.discover(svc));
        if (m.equals("POST") && p.equals("/api/lights/auto")) return Res.json(200, Lights.setAuto(svc, r.json()));
        if (m.equals("POST") && p.equals("/api/lights")) {
            if (!Lights.available(svc)) return Res.json(503, err("no_bulbs"));
            return Res.json(200, Lights.set(svc, r.json()));
        }
        return Res.json(404, err("not_found"));
    }

    /**
     * Anything the phone does wakes the TV first, as a remote press would: out of any screensaver
     * (ours or Amazon's) and back on if it dozed. Just ending the screensaver isn't enough: Android
     * then counts the TV as idle and turns the screen off.
     */
    private void wake() {
        android.os.PowerManager pm = (android.os.PowerManager) svc.getSystemService(Context.POWER_SERVICE);
        if (!Screensaver.isShowing() && pm != null && pm.isInteractive() && !dreaming()) return;
        try {
            if (Keys.send(svc, "key 224 0").startsWith("ok")) return; // KEYCODE_WAKEUP
        } catch (Exception ignored) { }
        Screensaver.dismiss();
    }

    /** Amazon's screensaver can be up too; its window belongs to the dream package. */
    private boolean dreaming() {
        String f = svc.front();
        return f != null && f.contains("screensaver");
    }

    private static final Map<String, Integer> KEYCODES = new HashMap<>();
    static {
        String[] names = {"up", "down", "left", "right", "select", "back", "menu", "play_pause", "rewind",
                "fast_forward", "next", "previous", "volume_up", "volume_down", "mute"};
        int[] codes = {19, 20, 21, 22, 23, 4, 82, 85, 89, 90, 87, 88, 24, 25, 164};
        for (int i = 0; i < names.length; i++) KEYCODES.put(names[i], codes[i]);
    }

    /** One KeyServer command; 503 needs_bootstrap until a phone has started it this boot. */
    /**
     * GET list, GET /thumb?name=, POST (body: the image; ?use=background also sets it),
     * DELETE ?name=, POST /background {"name": ... or null}.
     */
    private Res wallpapers(Req r) throws Exception {
        String m = r.method, p = r.path;
        if (m.equals("GET") && p.equals("/api/wallpapers")) return Res.json(200, Wallpapers.list(svc));
        if (m.equals("GET") && p.equals("/api/wallpapers/thumb")) {
            byte[] jpg = Wallpapers.thumb(r.q("name"));
            if (jpg == null) return Res.json(404, err("no_wallpaper"));
            Res res = new Res();
            res.type = "image/jpeg";
            res.body = jpg;
            return res;
        }
        if (m.equals("POST") && p.equals("/api/wallpapers")) {
            if (r.length <= 0) return Res.json(400, err("empty"));
            String name = Wallpapers.save(svc, r.stream, r.length);
            if (name == null) return Res.json(400, err("not_an_image"));
            if ("background".equals(r.q("use"))) Wallpapers.setBackground(svc, name);
            return Res.json(200, new JSONObject().put("ok", true).put("name", name));
        }
        if (m.equals("DELETE") && p.equals("/api/wallpapers")) {
            return Wallpapers.delete(svc, r.q("name")) ? Res.json(200, new JSONObject().put("ok", true))
                    : Res.json(404, err("no_wallpaper"));
        }
        if (m.equals("POST") && p.equals("/api/wallpapers/background")) {
            JSONObject b = r.json();
            String name = b.isNull("name") ? null : b.optString("name", null);
            if (name != null && Wallpapers.file(name) == null) return Res.json(404, err("no_wallpaper"));
            Wallpapers.setBackground(svc, name);
            return Res.json(200, new JSONObject().put("ok", true));
        }
        return Res.json(404, err("not_found"));
    }

    private Res keyCmd(String line) throws Exception {
        String reply;
        try {
            reply = Keys.send(svc, line);
        } catch (IOException e) {
            return Res.json(503, err("needs_bootstrap"));
        }
        // Unmappable characters (emoji, non-Latin) are skipped, not an error.
        return reply.startsWith("ok") || reply.equals("err unmapped") ? ok() : Res.json(503, err("keys_" + reply.replace("err ", "")));
    }

    // ---------------------------------------------------------------- pairing

    private Res pairRequest(Req r) throws JSONException {
        String device = r.json().optString("device", "a phone");
        String shown = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
        synchronized (pairing) {
            long now = System.currentTimeMillis();
            // The PIN on screen still stands (the phone reopened pairing): keep it and its tries left.
            if (pin != null && now < pinUntil) return ok();
            if (now < nextPinAt) {
                return Res.json(429, err("pair_cooldown").put("retry_after_s", (nextPinAt - now + 999) / 1000));
            }
            nextPinAt = now + PIN_COOLDOWN_MS;
            pin = shown;
            pinUntil = now + 120_000;
            pinTriesLeft = PIN_TRIES;
        }
        main.post(() -> svc.showPin(shown, device));
        return ok();
    }

    private Res pairConfirm(Req r) throws JSONException {
        String given = r.json().optString("pin");
        synchronized (pairing) {
            if (pin == null || System.currentTimeMillis() > pinUntil) return Res.json(403, err("pin_expired"));
            if (!MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                misses++;
                long wait = Math.min(PIN_COOLDOWN_MAX_MS, PIN_COOLDOWN_MS << Math.min((misses - 1) / PIN_TRIES, 7));
                nextPinAt = Math.max(nextPinAt, System.currentTimeMillis() + wait);
                // Out of tries: the PIN is gone and the phone has to ask for a new one.
                if (--pinTriesLeft <= 0) {
                    pin = null;
                    main.post(svc::hidePin);
                }
                return Res.json(403, err("bad_pin").put("tries_left", Math.max(pinTriesLeft, 0)));
            }
            pin = null;
            misses = 0;
            nextPinAt = 0;
        }
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        String token = sb.toString();
        Set<String> all = new HashSet<>(tokens());
        all.add(token);
        prefs().edit().putStringSet("tokens", all).apply();
        main.post(svc::hidePin);
        return Res.json(200, new JSONObject().put("token", token).put("name", tvName()));
    }

    private Set<String> tokens() {
        return prefs().getStringSet("tokens", new HashSet<>());
    }

    private SharedPreferences prefs() {
        return svc.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- apps

    private JSONArray apps() throws JSONException {
        PackageManager pm = svc.getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(svc.getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(svc.getPackageName());
        List<String> pinned = MainActivity.dockOrder(svc);
        JSONArray pinnedArr = new JSONArray(), rest = new JSONArray();
        Map<String, JSONObject> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, new JSONObject().put("pkg", pkg).put("label", String.valueOf(ri.loadLabel(pm)))
                    .put("color", String.format("#%06X", 0xFFFFFF & color(pkg, art(ri, pm))))
                    .put("pinned", pinned.contains(pkg)));
        }
        for (String pkg : pinned) if (byPkg.containsKey(pkg)) pinnedArr.put(byPkg.remove(pkg));
        for (JSONObject o : byPkg.values()) rest.put(o);
        for (int i = 0; i < rest.length(); i++) pinnedArr.put(rest.get(i));
        return pinnedArr;
    }

    private static Drawable art(ResolveInfo ri, PackageManager pm) {
        ActivityInfo ai = ri.activityInfo;
        Drawable d = ai.loadBanner(pm);
        if (d == null) d = ai.applicationInfo.loadBanner(pm);
        return d != null ? d : ri.loadIcon(pm);
    }

    private synchronized int color(String pkg, Drawable d) {
        Integer c = colors.get(pkg);
        if (c == null) { c = Look.sampleColor(d); colors.put(pkg, c); }
        return c;
    }

    private synchronized byte[] banner(String pkg) {
        if (banners.containsKey(pkg)) return banners.get(pkg);
        PackageManager pm = svc.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
        if (l.isEmpty()) return null;
        Bitmap bm = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Drawable d = art(l.get(0), pm);
        d.setBounds(0, 0, 320, 180);
        d.draw(new Canvas(bm));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        bm.recycle();
        banners.put(pkg, out.toByteArray());
        return banners.get(pkg);
    }

    String label(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = svc.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    boolean launch(String pkg) {
        PackageManager pm = svc.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        return true;
    }

    boolean open(String url, String pkg) {
        if (url == null || url.isEmpty()) return pkg != null && launch(pkg);
        // CLEAR_TASK: a running app otherwise comes to the front without seeing the link (see Catalog.open).
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (pkg != null && !pkg.isEmpty()) i.setPackage(pkg);
        try {
            svc.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    String tvName() {
        String n = Settings.Global.getString(svc.getContentResolver(), "device_name");
        return n == null || n.isEmpty() ? "Fire TV" : n;
    }

    static Res ok() throws JSONException { return Res.json(200, new JSONObject().put("ok", true)); }

    static JSONObject err(String e) {
        try { return new JSONObject().put("ok", false).put("error", e); }
        catch (JSONException x) { throw new IllegalStateException(x); }
    }

    static Res json(int code, Object o) { return Res.json(code, o); }
}
