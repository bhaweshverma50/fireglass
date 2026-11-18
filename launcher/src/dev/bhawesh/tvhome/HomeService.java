package dev.bhawesh.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.Context;

/**
 * Fire OS won't let a third-party app be Home (com.amazon.tv.launcher is a protected package), and
 * it consumes KEYCODE_HOME before accessibility key filtering sees it. So this service watches for
 * the Amazon launcher's window and bounces back here. Android holds a plain startActivity for ~5 s
 * after a Home press (app-switch guard), so the bounce goes through an alarm instead: system_server
 * sends the PendingIntent and is exempt. The alarm fires at once only with the global
 * alarm_manager_constants min_futurity=0 (build-and-install.sh sets it); without it, it waits ~5 s.
 * A loading screen (Look.loading) covers Amazon's home until ours has drawn.
 */
public class HomeService extends AccessibilityService implements RemoteServer.Host {
    static final String TAG = "TvHome";
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    static final String FIRE_SETTINGS = "com.amazon.tv.settings.v2";
    // Shown only on the Settings tab of Amazon's home.
    static final String SETTINGS_TAB_MARKER = "Search Your Settings";
    private static HomeService self;

    private final Handler handler = new Handler();
    private final Runnable hide = this::removeMask;
    private View pinView;
    // The cover window is added once and only shown or hidden, so a Home press creates no window.
    private FrameLayout cover;
    private WindowManager.LayoutParams coverLp;
    private boolean covering;
    private long lastBounce, lastSettingsAt, pausedUntil;
    private final long[] recentBounces = new long[3];
    private RemoteServer remote;
    private final Runnable pinTimeout = this::hidePin;
    private String front = "";          // package of the window in front
    private boolean settingsMode;       // user is in Amazon's Settings tab on purpose
    private long settingsSince;
    private final Runnable settingsWatch = this::checkSettings;
    // Alexa voice searches show Amazon's results page; read what was said and search here instead.
    private static final String VOICE_RESULTS = "SearchResultsActivity";
    private final Runnable voiceWatch = this::readVoiceQuery;
    private int voiceTries;
    private long voiceQuietUntil;            // gave up on this results page: leave it be for a while
    private String lastVoice = "";
    private long lastVoiceAt;

    @Override
    protected void onServiceConnected() {
        self = this;
        addCover();
        remote = new RemoteServer(this, this);
        remote.start();
    }

    @Override
    public boolean onUnbind(Intent i) {
        self = null;
        handler.removeCallbacks(hide);
        if (cover != null) ((WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).removeViewImmediate(cover);
        cover = null;
        covering = false;
        hidePin();
        if (remote != null) remote.stop();
        return super.onUnbind(i);
    }

    /** Remote buttons given another job (Buttons); everything else goes on as usual. */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        return Buttons.onKey(this, e);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        // A system prompt (Allow USB debugging?, install, permissions): stand down, never cover it.
        CharSequence ecls = e.getClassName();
        if (SystemUi.isPrompt(pkg, ecls == null ? null : ecls.toString())) {
            SystemUi.opened();
            standDown();
            if (!pkg.equals("android") && !pkg.equals("com.android.systemui")) front = pkg;
            return;
        }
        if (pkg.equals(getPackageName())) SystemUi.gone(); // in front again: the prompt was answered
        // Popups and toasts from the system don't count as what's in front.
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return;
        front = pkg;
        if (pkg.equals(FIRE_SETTINGS)) lastSettingsAt = SystemClock.uptimeMillis();
        // Opened something else first: drop a queued bounce so it can't pull the user back.
        if (!pkg.equals(AMAZON_HOME) && !pkg.equals(getPackageName())) arrived(this);
        if (settingsMode) {
            // Amazon settings and its sub-screens are fine; anything else ends the visit.
            if (pkg.equals(AMAZON_HOME) || pkg.equals(FIRE_SETTINGS)) return;
            endSettings();
        }
        if (!pkg.equals(AMAZON_HOME)) return;
        // Only its home screen; its other screens (app library, search) open normally.
        CharSequence cls = e.getClassName();
        if (cls != null && cls.toString().contains(VOICE_RESULTS)) {
            // Taking the cover down re-focuses Amazon's page; after giving up on it, don't start over.
            if (SystemClock.uptimeMillis() < voiceQuietUntil) return;
            // Cover Amazon's page at once and read what was said from underneath the cover.
            voiceTries = 0;
            showMask();
            handler.removeCallbacks(voiceWatch);
            handler.post(voiceWatch);
            return;
        }
        if (cls == null || !cls.toString().contains("HomeActivity")) return;
        Log.i(TAG, "amazon home surfaced: " + cls);
        bounce();
    }

    /**
     * Display & sounds, Preferences, Accessibility and Account exist only as the Settings tab of
     * Amazon's home (their screens need Amazon's signature permission, even from adb shell).
     * Open that tab and stand aside; navigating inside Amazon's home sends no window events, so
     * poll for the tab's marker and come back home once it's gone (Back or Home pressed).
     */
    static boolean openAmazonSettings(Context c) {
        if (self == null) return false;
        self.settingsMode = true;
        self.settingsSince = android.os.SystemClock.uptimeMillis();
        self.handler.removeCallbacks(self.settingsWatch);
        self.handler.postDelayed(self.settingsWatch, 2500);
        c.startActivity(new Intent().setClassName(AMAZON_HOME, AMAZON_HOME + ".ui.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    private void checkSettings() {
        if (!settingsMode) return;
        if (front.equals(AMAZON_HOME)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean onTab = root != null && !root.findAccessibilityNodeInfosByText(SETTINGS_TAB_MARKER).isEmpty();
            if (root != null) root.recycle();
            if (!onTab) {
                Log.i(TAG, "left amazon settings");
                endSettings();
                bounce();
                return;
            }
        }
        handler.postDelayed(settingsWatch, 800);
    }

    private void endSettings() {
        settingsMode = false;
        handler.removeCallbacks(settingsWatch);
    }

    /**
     * Back to Fireglass. The guards are cleanyourtv's: Amazon sends two home events per press
     * (1.5 s dedupe); leaving a Settings screen passes through Amazon's home (2 s grace); and a
     * loop of bounces (3 in 10 s, say an app fighting back) stands aside for a minute.
     */
    private void bounce() {
        // A system prompt is up: leave the screen to it. Looks at the windows too, in case its
        // own event was missed (it opened while the service was rebinding, say).
        if (!SystemUi.active() && promptWindowUp()) SystemUi.opened();
        if (SystemUi.holdHome()) {
            Log.i(TAG, "system prompt up: not bouncing");
            standDown();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBounce < 1500 || now < pausedUntil) return;
        if (now - lastSettingsAt < 2000) {
            // Opening Settings passes through Amazon's home: look again once that has settled.
            handler.postDelayed(() -> { if (front.equals(AMAZON_HOME) && !settingsMode) bounce(); },
                    2000 - (now - lastSettingsAt));
            return;
        }
        System.arraycopy(recentBounces, 1, recentBounces, 0, recentBounces.length - 1);
        recentBounces[recentBounces.length - 1] = now;
        if (recentBounces[0] != 0 && now - recentBounces[0] < 10_000) {
            pausedUntil = now + 60_000;
            java.util.Arrays.fill(recentBounces, 0);
            Log.w(TAG, "home loop: standing aside for a minute");
            return;
        }
        lastBounce = now;
        showMask();
        // Android 10+ (Fire OS 8) blocks this background start unless our uid has a visible window:
        // fire once the cover has drawn, so it counts as one. Fire OS 7 has no such check.
        long delay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? COVER_DRAWN_MS : 0;
        ((AlarmManager) getSystemService(android.content.Context.ALARM_SERVICE)).setExact(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + delay, launch(this));
        // The Amazon launcher holds ~130 MB; reclaim it once it's behind us (after the 5 s guard).
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        handler.postDelayed(() -> am.killBackgroundProcesses(AMAZON_HOME), 8000);
    }

    /** An application window of a system prompt is on screen (see SystemUi). */
    private boolean promptWindowUp() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                root.recycle();
                if (p != null && SystemUi.isPrompt(p.toString(), null)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /** A prompt is on screen: cover down, pairing card away, no queued bounce, no screensaver over it. */
    private void standDown() {
        Log.i(TAG, "system prompt opened: covers down");
        removeMask();
        hidePin();
        arrived(this);
        Screensaver.dismiss();
    }

    /** The page puts the words as heard ("search for mirzapur") at the top; it may take a moment to fill. */
    private void readVoiceQuery() {
        // Every window on screen, not the active one: Alexa's overlay or the cover can hold focus.
        String said = null;
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            if (said == null) {
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(AMAZON_HOME + ":id/search_text")) {
                    if (said == null && n.getText() != null && n.getText().length() > 0) said = n.getText().toString();
                    n.recycle();
                }
            }
            root.recycle();
        }
        if (said == null || said.trim().isEmpty()) {
            if (++voiceTries < 12) handler.postDelayed(voiceWatch, 250);
            else { Log.w(TAG, "voice search: nothing to read on Amazon's page"); endVoice(true); }
            return;
        }
        String q = Search.spokenQuery(said);
        long now = SystemClock.uptimeMillis();
        // The same words again straight away is Amazon's page resurfacing, not a new search.
        if (q.isEmpty() || (q.equalsIgnoreCase(lastVoice) && now - lastVoiceAt < 8000)) { endVoice(true); return; }
        endVoice(false);
        lastVoice = q;
        lastVoiceAt = now;
        Log.i(TAG, "voice search: " + q);
        // Same path as the Home bounce: under the cover, an alarm starts the search.
        Intent i = new Intent(this, Search.class).putExtra(Search.QUERY, q).putExtra(Search.FROM_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        long delay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? COVER_DRAWN_MS : 0;
        ((AlarmManager) getSystemService(android.content.Context.ALARM_SERVICE)).setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, pi);
    }

    /** Done with Amazon's page; with nothing to search, take the cover down and leave it showing. */
    private void endVoice(boolean uncover) {
        if (!uncover) return;
        voiceQuietUntil = SystemClock.uptimeMillis() + 15_000;
        removeMask();
    }

    static PendingIntent launch(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    /** Home is in front (or the user went elsewhere): cancel a bounce that hasn't fired yet. */
    static void arrived(Context c) {
        ((AlarmManager) c.getSystemService(android.content.Context.ALARM_SERVICE)).cancel(launch(c));
    }

    static boolean maskShowing() {
        return (self != null && self.covering) || Legacy.covering();
    }

    /** Called by MainActivity once it is drawn again. */
    static void hideMask() {
        if (self != null) self.removeMask();
        Legacy.uncover();
    }

    private static final long COVER_DRAWN_MS = 300;

    private static final int PASSIVE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

    private void addCover() {
        FrameLayout v = new FrameLayout(this);
        v.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN | PASSIVE,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try {
            ((WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).addView(v, lp);
        } catch (RuntimeException ex) {
            Log.w(TAG, "cover failed", ex);
            return;
        }
        cover = v;
        coverLp = lp;
    }

    private void showMask() {
        if (SystemUi.active()) return; // never over a system prompt
        if (covering || cover == null) return;
        covering = true;
        cover.animate().cancel();
        cover.setAlpha(1f);
        // Built per show, not kept: its sky and shimmer would keep animating while hidden.
        cover.removeAllViews();
        cover.addView(Look.loading(this, MainActivity.dockCount, MainActivity.accent));
        // Focusable while showing, so remote keys don't reach Amazon's home underneath.
        coverLp.flags &= ~PASSIVE;
        ((WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).updateViewLayout(cover, coverLp);
        cover.setVisibility(View.VISIBLE);
        // Never strand the remote behind the loading screen if the bounce fails.
        handler.postDelayed(hide, 8000);
    }

    private void removeMask() {
        handler.removeCallbacks(hide);
        if (!covering) return;
        covering = false;
        cover.animate().alpha(0).setDuration(180).withEndAction(() -> {
            if (covering || cover == null) return;
            cover.setVisibility(View.GONE);
            cover.removeAllViews();
            coverLp.flags |= PASSIVE;
            ((WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).updateViewLayout(cover, coverLp);
        });
    }

    /** Package of the app in front, for the phone's "now playing". */
    @Override public String front() { return front; }

    /** Home from the phone: straight to this launcher (no Home key, so no 5 s app-switch hold). */
    @Override public void goHome() {
        if (SystemUi.active()) return; // not over (or past) a system prompt
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    /** The pairing code card (also Legacy's on Fire OS 5). */
    static LinearLayout pinCard(Context c, String pin, String device) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Look.dp(c, 26);
        card.setPadding(pad, Look.dp(c, 22), pad, Look.dp(c, 24));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Look.dp(c, 28));
        bg.setColor(0xEB121420);
        bg.setStroke(Look.dp(c, 1), 0x33FFFFFF);
        card.setBackground(bg);
        TextView head = Look.text(c, Look.sans(c, 600), 16, 0xE6FFFFFF);
        head.setText("Pair " + device);
        TextView code = Look.text(c, Look.serif(c), 72, 0xFFFFFFFF);
        code.setText(pin);
        code.setLetterSpacing(0.18f);
        TextView hint = Look.text(c, Look.sans(c, 500), 14, 0x99FFFFFF);
        hint.setText("Type this code on your phone");
        hint.setSingleLine(true);
        // Android 5 measured the card a few characters short of the hint ("…on your phor").
        if (android.os.Build.VERSION.SDK_INT < 23) card.setMinimumWidth(Look.dp(c, 270));
        card.addView(head);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.setMargins(0, Look.dp(c, 10), 0, Look.dp(c, 8));
        card.addView(code, cp);
        card.addView(hint);
        return card;
    }

    /** Pairing code, top right over whatever is on screen, for two minutes. */
    @Override public void showPin(String pin, String device) {
        hidePin();
        if (SystemUi.active()) return;
        LinearLayout card = pinCard(this, pin, device);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Look.dp(this, 48);
        lp.y = Look.dp(this, 40);
        try {
            ((WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).addView(card, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "pin overlay failed", e);
            return;
        }
        pinView = card;
        card.setAlpha(0f);
        card.setTranslationY(-Look.dp(this, 12));
        card.animate().alpha(1f).translationY(0f).setDuration(Look.ms(this, 260)).start();
        handler.postDelayed(pinTimeout, 120_000);
    }

    @Override public void hidePin() {
        handler.removeCallbacks(pinTimeout);
        View v = pinView;
        if (v == null) return;
        pinView = null;
        v.animate().alpha(0f).setDuration(Look.ms(this, 180))
                .withEndAction(() -> ((WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).removeViewImmediate(v)).start();
    }

    @Override
    public void onInterrupt() { }
}
