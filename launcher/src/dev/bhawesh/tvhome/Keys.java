package dev.bhawesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Client for {@link KeyServer}, which a paired phone starts (see {@link #bootstrapCommand}). */
final class Keys {
    private Keys() { }

    private static Socket sock;
    private static BufferedReader in;
    private static OutputStream out;

    /** Sends one command line; returns KeyServer's reply ("ok", "err …"). */
    static synchronized String send(Context c, String line) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                if (sock == null) connect(c);
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String r = in.readLine();
                if (r == null) throw new IOException("key server closed");
                return r;
            } catch (IOException e) {
                close();
                if (attempt == 1) throw e;
            }
        }
    }

    /** "ready", or "needs_bootstrap" until the phone has started KeyServer (see bootstrapCommand). */
    static synchronized String status(Context c) {
        if (sock != null) return "ready";
        try {
            connect(c);
            return "ready";
        } catch (Exception e) {
            close();
            return "needs_bootstrap";
        }
    }

    /**
     * Shell command that starts KeyServer. Fire OS refuses adb connections made from the stick
     * itself (loopback and its own LAN address alike), so a paired phone runs this over network
     * adb, once per boot (docs/remote-api.md).
     */
    static String bootstrapCommand(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            // Fire OS 5's toolbox has no pkill or setsid, and adbd kills the shell's process group
            // when the phone hangs up. `set -m` gives the background job a group of its own. An
            // older injector still running is told to quit by connect().
            return "CLASSPATH=" + c.getApplicationInfo().sourceDir + " sh -c 'set -m; nohup app_process /system/bin"
                    + " dev.bhawesh.tvhome.KeyServer " + secret(c) + " </dev/null >/dev/null 2>&1 &'";
        }
        // Anchored: the shell running this line has the same text in its own command line.
        return "pkill -f '^app_process /system/bin dev.bhawesh.tvhome.KeyServer'; CLASSPATH=" + c.getApplicationInfo().sourceDir
                // In a new session, started before this command returns: the phone's adb shell has a
                // terminal, and closing it took the injector down with it (nohup wasn't enough).
                + " setsid sh -c 'app_process /system/bin dev.bhawesh.tvhome.KeyServer " + secret(c)
                + " </dev/null >/dev/null 2>&1 &'";
    }

    private static void connect(Context c) throws Exception {
        open(secret(c));
        if (("ok " + KeyServer.VERSION).equals(ask("version"))) return;
        ask("quit"); // injector from an older install: the phone starts the current one
        close();
        throw new IOException("key server outdated");
    }

    private static void open(String secret) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", KeyServer.PORT), 500);
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        sock = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out = s.getOutputStream();
        if (!"ok".equals(ask("auth " + secret))) { close(); throw new IOException("key server auth"); }
    }

    private static String ask(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return in.readLine();
    }

    private static void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        sock = null;
    }

    /** Shared secret so other apps on the stick can't drive the injector. */
    private static String secret(Context c) {
        SharedPreferences p = c.getSharedPreferences("keys", Context.MODE_PRIVATE);
        String s = p.getString("secret", null);
        if (s == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            s = sb.toString();
            p.edit().putString("secret", s).apply();
        }
        return s;
    }
}
