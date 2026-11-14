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
    private static Legacy self;
    private View cover; // Look.loading over Amazon's pages until ours has drawn
    private final Runnable uncoverTask = this::removeCover;
    // Heartbeat: a line of our own in the log being followed. If the reader stops seeing it
    // (it has wedged, leaving Home with Amazon), the reader is restarted.
    private static final String PING = "fireglass ping ";
    private volatile int pingSeen;
    private int pingSent;
    private final Runnable checkPing = () -> {
        if (pingSeen == pingSent) return;
        Log.w(TAG, "log follower stopped hearing; restarting it");
        Process p = logcat;
        if (p != null) p.destroy();
    };
    private final Runnable sendPing = new Runnable() {
        @Override public void run() {
            Log.i("ActivityManager", PING + (++pingSent));
            main.postDelayed(checkPing, 3000);
            main.postDelayed(this, 15_000);
        }
    };

    Legacy(Context c) {
        this.c = c;
        self = this;
        remote = new RemoteServer(c, this);
        remote.start();
        // Amazon's home is up before this listener is bound at boot, and no Home press follows it.
        if (SystemClock.elapsedRealtime() < 180_000) goHome();
        Thread t = new Thread(this::follow, "home-watch");
        t.setDaemon(true);
        t.start();
        main.postDelayed(sendPing, 15_000);
    }

    void stop() {
        if (self == this) self = null;
        main.removeCallbacks(sendPing);
        main.removeCallbacks(checkPing);
        main.post(this::removeCover);
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
                // -T 1 replays one old line at once; whatever arrives in the first moment is history.
                long since = SystemClock.uptimeMillis() + 700;
                for (String l; (l = r.readLine()) != null; ) {
                    if (l.startsWith(PING)) {
                        try { pingSeen = Integer.parseInt(l.substring(PING.length()).trim()); } catch (NumberFormatException ignored) { }
                        continue;
                    }
                    boolean first = SystemClock.uptimeMillis() < since;
                    if (!l.startsWith("START u0 {")) continue;
                    Matcher m = CMP.matcher(l);
                    if (!m.find()) continue;
                    String pkg = m.group(1);
                    front = pkg;
                    boolean home = pkg.equals(HomeService.AMAZON_HOME) && l.contains("cat=[android.intent.category.HOME]");
                    if (home && !first) main.post(this::bounce);
                    // Alexa's voice search (and Amazon's own) opens its results page: ours instead.
                    if (!first && pkg.equals(HomeService.AMAZON_HOME) && l.contains("SearchResults")) {
                        new Thread(this::voiceSearch, "voice").start();
                    }
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
        } else {
            showCover(false); // the slow way: Amazon's home would show for seconds
            new Thread(() -> {
                String r;
                try { r = Keys.send(c, "resume"); } catch (Exception e) { r = "no key server"; }
                Log.i(TAG, "home pressed, app switch hold: " + r);
                main.post(this::goHome);
            }, "resume").start();
        }
        // Amazon's launcher holds ~75 MB here; reclaim it once it's behind us.
        main.postDelayed(() -> ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE))
                .killBackgroundProcesses(HomeService.AMAZON_HOME), 8000);
    }

    /**
     * The hold doesn't cover an app bringing its own task forward (cleanyourtv's trick): ~0.5 s,
     * no KeyServer needed. False when Fireglass has no live task to bring.
     */
    private boolean toFront() {
        for (android.app.ActivityManager.AppTask t : ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getAppTasks()) {
            try {
                // A task whose activities were dropped for memory (Netflix on a 1 GB stick) has id -1:
                // bringing it forward relaunches it, and that launch is held like any other.
                if (t.getTaskInfo().id == -1) continue;
                t.moveToFront();
                Log.i(TAG, "home pressed, our task to front");
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "task to front", e);
            }
        }
        return false;
    }

    /**
     * Fire OS 5 lets no app read Amazon's results page, but KeyServer (shell) may: it reads the
     * words there and Search opens with them. Without KeyServer (the phone hasn't started it since
     * boot) Amazon's page stays.
     */
    private void voiceSearch() {
        if (!"ready".equals(Keys.status(c))) {
            Log.i(TAG, "voice search: no key server, Amazon's page stays");
            return;
        }
        main.post(() -> showCover(true)); // Amazon's page takes ~2 s to show the words; keep it out of sight
        for (int i = 0; i < 3 && running; i++) { // KeyServer looks for up to 3 s each time
            String r;
            try { r = Keys.send(c, "searched"); } catch (Exception e) { break; }
            if (r.startsWith("err unknown")) {
                // An older KeyServer from before an update: let it go, the phone's next bootstrap starts this one.
                Log.i(TAG, "voice search: key server too old");
                try { Keys.send(c, "quit"); } catch (Exception ignored) { }
                break;
            }
            if (!r.startsWith("ok ")) continue;
            String q = Search.spokenQuery(r.substring(3));
            if (q.isEmpty()) break;
            Log.i(TAG, "voice search: " + q);
            c.startActivity(new Intent(c, Search.class).putExtra(Search.QUERY, q).putExtra(Search.FROM_VOICE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            // Amazon's launcher holds ~75 MB; it isn't needed behind our results.
            main.postDelayed(() -> ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE))
                    .killBackgroundProcesses(HomeService.AMAZON_HOME), 8000);
            return;
        }
        Log.w(TAG, "voice search: nothing to read on Amazon's page");
        main.post(this::removeCover);
    }

    /** True while the loading cover is up (MainActivity then reveals itself and lifts it). */
    static boolean covering() {
        Legacy l = self;
        return l != null && l.cover != null;
    }

    static void uncover() {
        Legacy l = self;
        if (l != null) l.main.post(l::removeCover);
    }

    /** Android 5 grants SYSTEM_ALERT_WINDOW at install: HomeService's loading screen, or Search's for a voice search. */
    private void showCover(boolean search) {
        main.removeCallbacks(uncoverTask);
        // Never strand the remote behind it. A voice search on a cold Amazon page can take ~6 s to read.
        main.postDelayed(uncoverTask, search ? 10_000 : 6000);
        if (cover != null) return;
        View v = search ? Look.searchLoading(c, MainActivity.accent, android.os.Build.VERSION.SDK_INT < 23)
                : Look.loading(c, MainActivity.dockCount, MainActivity.accent);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                // Not focusable: KeyServer reads the active window, which must stay Amazon's page.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try {
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(v, lp);
            cover = v;
            Log.i(TAG, "cover up");
        } catch (RuntimeException e) {
            Log.w(TAG, "cover failed", e);
        }
    }

    private void removeCover() {
        main.removeCallbacks(uncoverTask);
        View v = cover;
        if (v == null) return;
        cover = null;
        Log.i(TAG, "cover down");
        v.animate().alpha(0f).setDuration(180).withEndAction(() ->
                ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(v));
    }

    /** After an update Android leaves Amazon's home up and no Home press follows: come back. */
    public static final class Updated extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (android.os.Build.VERSION.SDK_INT >= 23) return; // HomeService covers Fire OS 7 and later
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
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
