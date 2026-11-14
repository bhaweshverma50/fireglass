package dev.bhawesh.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fire OS 5 (Android 5.1) stand-in for HomeService. Amazon refuses third-party accessibility
 * services there (writes to enabled_accessibility_services are denied even with
 * WRITE_SECURE_SETTINGS), so HomeService never runs. Instead this follows ActivityManager's log
 * (READ_LOGS, granted at install): every "START u0 {...}" line says what came to the front, and a
 * HOME intent for Amazon's launcher is the Home button, so Fireglass goes back on top. It also
 * hosts the phone remote. Lives in Watching, the notification listener, which the system keeps
 * bound from boot.
 */
final class Legacy implements RemoteServer.Host {
    private static final String TAG = "TvHome";
    private static final Pattern CMP = Pattern.compile("cmp=([\\w.]+)/");

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteServer remote;
    private final Runnable pinTimeout = this::hidePin;
    private volatile String front = "";
    private volatile boolean running = true;
    private volatile Process logcat;
    private View pin;

    Legacy(Context c) {
        this.c = c;
        remote = new RemoteServer(c, this);
        remote.start();
        // Amazon's home is up before this listener is bound at boot, and no Home press follows it.
        if (SystemClock.elapsedRealtime() < 180_000) goHome();
        Thread t = new Thread(this::follow, "home-watch");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        Process p = logcat;
        if (p != null) p.destroy();
        remote.stop();
        main.post(this::hidePin);
    }

    private void follow() {
        while (running) {
            try {
                // -T 1: only what's logged from now on (plus one line), not the whole buffer.
                Process p = logcat = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-v", "raw", "-T", "1", "ActivityManager:I", "*:S"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                boolean first = true;
                for (String l; (l = r.readLine()) != null; ) {
                    if (!l.startsWith("START u0 {")) continue;
                    Matcher m = CMP.matcher(l);
                    if (!m.find()) continue;
                    String pkg = m.group(1);
                    front = pkg;
                    boolean home = pkg.equals(HomeService.AMAZON_HOME) && l.contains("cat=[android.intent.category.HOME]");
                    if (home && !first) main.post(this::bounce);
                    first = false;
                }
            } catch (IOException e) {
                Log.w(TAG, "can't follow the log (READ_LOGS not granted?)", e);
            }
            if (running) SystemClock.sleep(2000);
        }
    }

    /**
     * Home pressed: back on top. Android holds app launches for ~5 s after Home, so bring our task
     * forward (not held). Without a task, KeyServer (when the phone started it) lifts the hold first;
     * Android 5 doesn't release a launch that is already waiting, so the lift comes before the start.
     */
    private void bounce() {
        if (toFront()) {
            // Ours is in front now, so starting it isn't held: that clears Search or Settings off the top.
            main.postDelayed(this::goHome, 300);
        } else new Thread(() -> {
            String r;
            try { r = Keys.send(c, "resume"); } catch (Exception e) { r = "no key server"; }
            Log.i(TAG, "home pressed, app switch hold: " + r);
            main.post(this::goHome);
        }, "resume").start();
        // Amazon's launcher holds ~75 MB here; reclaim it once it's behind us.
        main.postDelayed(() -> ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE))
                .killBackgroundProcesses(HomeService.AMAZON_HOME), 8000);
    }

    /**
     * The hold doesn't cover an app bringing its own task forward (cleanyourtv's trick): ~0.5 s,
     * no KeyServer needed. False when Fireglass has no task to bring.
     */
    private boolean toFront() {
        for (android.app.ActivityManager.AppTask t : ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getAppTasks()) {
            try {
                t.moveToFront();
                Log.i(TAG, "home pressed, our task to front");
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "task to front", e);
            }
        }
        return false;
    }

    @Override public String front() { return front; }

    @Override public void goHome() {
        c.startActivity(new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override public void showPin(String code, String device) {
        hidePin();
        View card = HomeService.pinCard(c, code, device);
        // Android 5 grants SYSTEM_ALERT_WINDOW at install.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Look.dp(c, 48);
        lp.y = Look.dp(c, 40);
        try {
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(card, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "pin overlay failed", e);
            return;
        }
        pin = card;
        main.postDelayed(pinTimeout, 120_000);
    }

    @Override public void hidePin() {
        main.removeCallbacks(pinTimeout);
        View v = pin;
        if (v == null) return;
        pin = null;
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(v);
    }
}
