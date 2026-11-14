package dev.bhawesh.tvhome;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Key injector that runs as the shell user, not inside the app (like scrcpy's server):
 * {@code CLASSPATH=<this apk> app_process /system/bin dev.bhawesh.tvhome.KeyServer <secret>}.
 * Shell may inject input, apps may not; and `input keyevent` costs ~0.8 s a press on this stick
 * because it boots a new runtime every time, while this stays up and injects in ~1 ms.
 *
 * Listens on 127.0.0.1:{@link #PORT}. Line protocol, first line must be "auth <secret>":
 *   key <keycode> <long 0|1>   text <utf-8 text>   stop <package>   resume   version   quit
 * Each command is answered with "ok" or "err <reason>".
 */
public final class KeyServer {
    static final int PORT = 8091;
    static final String VERSION = "3";
    // ~20 MB while alive. Restarting needs the phone, so it stays up for a day of no use.
    private static final int IDLE_EXIT_MS = 24 * 60 * 60 * 1000;

    private static Object inputManager;
    private static Method inject;

    public static void main(String[] args) throws Exception {
        String secret = args.length > 0 ? args[0] : "";
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        inputManager = im.getDeclaredMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);

        ServerSocket server;
        try {
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        } catch (java.io.IOException alreadyRunning) {
            return;
        }
        server.setSoTimeout(IDLE_EXIT_MS);
        while (true) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(IDLE_EXIT_MS);
                serve(s, secret);
            } catch (java.net.SocketTimeoutException idle) {
                System.exit(0);
            } catch (Exception ignored) {
                // One bad client must not take the injector down.
            }
        }
    }

    private static void serve(Socket s, String secret) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = s.getOutputStream();
        if (!("auth " + secret).equals(in.readLine())) {
            out.write("err auth\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        out.write("ok\n".getBytes(StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String reply = "ok";
            try {
                if (line.startsWith("key ")) {
                    String[] p = line.split(" ");
                    key(Integer.parseInt(p[1]), p.length > 2 && p[2].equals("1"));
                } else if (line.startsWith("text ")) {
                    reply = text(line.substring(5)) ? "ok" : "err unmapped";
                } else if (line.startsWith("stop ")) {
                    // Apps can't force-stop other apps; shell can.
                    String pkg = line.substring(5);
                    if (!pkg.matches("[A-Za-z0-9_.]+")) reply = "err pkg";
                    else Runtime.getRuntime().exec(new String[]{"am", "force-stop", pkg}).waitFor();
                } else if (line.equals("resume")) {
                    // Android holds app launches for ~5 s after a Home press; shell may lift the hold.
                    // Fire OS 5 needs this: Legacy's launch home otherwise waits the full 5 s.
                    Class<?> amn = Class.forName("android.app.ActivityManagerNative");
                    Object am = amn.getMethod("getDefault").invoke(null);
                    am.getClass().getMethod("resumeAppSwitches").invoke(am);
                } else if (line.equals("version")) {
                    reply = "ok " + VERSION;
                } else if (line.equals("quit")) {
                    out.write("ok\n".getBytes(StandardCharsets.UTF_8));
                    System.exit(0);
                } else {
                    reply = "err unknown";
                }
            } catch (Exception e) {
                reply = "err " + e.getClass().getSimpleName();
            }
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void key(int code, boolean longPress) throws Exception {
        long down = SystemClock.uptimeMillis();
        send(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        if (longPress) {
            // Repeats with the long-press flag, as a held remote button sends them.
            for (int r = 1; r <= 12; r++) {
                Thread.sleep(50);
                send(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, r, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        r == 10 ? KeyEvent.FLAG_LONG_PRESS : 0, InputDevice.SOURCE_KEYBOARD));
            }
        }
        send(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    /** Types text as key presses; characters the virtual keyboard map lacks are skipped. */
    private static boolean text(String t) throws Exception {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        boolean all = true;
        for (char ch : t.toCharArray()) {
            KeyEvent[] events = map.getEvents(new char[]{ch});
            if (events == null) { all = false; continue; }
            for (KeyEvent e : events) send(e);
        }
        return all;
    }

    private static void send(KeyEvent e) throws Exception {
        inject.invoke(inputManager, e, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
    }
}
