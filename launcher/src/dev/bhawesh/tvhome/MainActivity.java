package dev.bhawesh.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Content-first home. Top: time, greeting, date and weather; the nav capsule. The first screen is
 * the featured title (HeroView backdrop, name, facts, synopsis, an "Open in <app>" button and page
 * dots), cycling through what's trending on the apps installed here; focusing an app narrows it
 * to that app's titles, like Apple TV's top shelf. Along the bottom, every app in one row. Below:
 * recently opened titles and a row per app. The live sky shows wherever there is no backdrop.
 */
public class MainActivity extends Activity {

    /** Read by the loading screen (HomeService) so it matches what this screen will show. */
    static int dockCount = 6;
    static int accent = 0xFF3A3A3A;

    private final List<String> dockPkgs = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
        long lastUsed; // for the switcher
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final Map<String, App> apps = new HashMap<>();
    private final List<View> appCards = new ArrayList<>();
    private final List<View> navItems = new ArrayList<>();
    // A title card's picture's main colour, for the lights' Match the home.
    private final Map<View, Integer> artColors = new HashMap<>();
    // Top bar as icons only (HomeSettings): names kept here, shown in a tag under the focused one.
    private final Map<TextView, String> navLabels = new HashMap<>();
    private boolean iconsOnly;
    private TextView navTip;
    private final List<List<View>> sectionCards = new ArrayList<>();
    // Poster images per row, loaded only near where focus is and dropped while away in an app:
    // ~80 decoded posters held up front cost ~25 MB on a 1 GB stick.
    private final List<List<Runnable>> rowLoaders = new ArrayList<>();
    private final List<List<ImageView>> rowImages = new ArrayList<>();
    private final Set<Integer> rowsLoaded = new HashSet<>();

    private SkyView sky;
    private HeroView hero;
    private HomeScrollView scroll;
    private LinearLayout content, appsRow, info, chips, sections;
    private View dim, nav, puck;
    private Look.TopBar bar;
    private TextView heroLabel, heroTitle, heroOverview, heroButton, wifiItem;
    private Dots dots;
    private Look.Icon wifiIcon, playIcon;
    private ValueAnimator puckAnim;
    private PopupWindow options;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private int heroW, heroH; // baked backdrop size: half the screen

    // Featured titles: everything trending here, or just the focused app's.
    private JSONArray recs = new JSONArray();
    private final List<JSONObject> heroItems = new ArrayList<>();
    private int heroIndex;
    private String heroSource; // pkg the hero is narrowed to, or null for all
    private App heroApp;       // focused app with nothing trending on it
    private final Runnable advance = () -> { if (canAdvance()) showHero(heroIndex + 1, 1); scheduleAdvance(); };
    private Runnable pendingSource;

    private String focusPkg;   // last focused app package or title id
    private int travel;        // last D-pad press: -1 left, 1 right, 0 up or down
    private boolean firstShow = true, resumed, fast;
    private boolean minimal, calm;      // HomeSettings: no featured block; no streaks or springs
    private int appliedSettings = -1;
    private TextView appTitle;          // minimal mode: the focused app's name over the background
    private long lastKeyAt;
    private int packageSeq;

    // Internet, not just Wi-Fi: Fire OS marks the network validated once its probe gets through.
    private boolean online = true;
    private ConnectivityManager.NetworkCallback netWatch;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { bar.update(MainActivity.this); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        dockPkgs.addAll(dockOrder(this));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        heroW = getResources().getDisplayMetrics().widthPixels / 2;
        heroH = getResources().getDisplayMetrics().heightPixels / 2;
        setContentView(buildUi());
        buildApps();
        showHero(0, 0);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeService.arrived(this);
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        watchNetwork();
        resumed = true;
        Lights.atHome(this, true);
        bar.update(this);
        updateWifi();
        if (HomeSettings.version(this) != appliedSettings) applySettings();
        showSky(true);
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        loadContent();
        restoreFocus();
        scheduleAdvance();

        boolean masked = HomeService.maskShowing();
        if (masked || firstShow) {
            // Coming out of the loading screen the top bar is already up; the rest rises in.
            boolean full = firstShow && !masked;
            firstShow = false;
            prepareEntrance(full);
            reveal = () -> { reveal = null; HomeService.hideMask(); playEntrance(full); };
            // Out of the loading screen: lift it once our window is on screen (onEnterAnimationComplete);
            // the delay is only a fallback in case that callback never comes.
            handler.removeCallbacks(revealTask);
            handler.postDelayed(revealTask, masked ? 400 : 0);
        }
    }

    private Runnable reveal;
    private final Runnable revealTask = this::revealNow;

    private void revealNow() {
        handler.removeCallbacks(revealTask);
        if (reveal != null) reveal.run();
    }

    @Override
    public void onEnterAnimationComplete() {
        revealNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        Lights.atHome(this, false);
        sky.setRunning(false);
        handler.removeCallbacks(advance);
        unregisterReceiver(tick);
        if (netWatch != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netWatch);
        netWatch = null;
        if (options != null) options.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home: back to the first screen, on an app.
        if (options != null) options.dismiss();
        if (switcher != null) hideSwitcher(null);
        if (focusPkg != null && !apps.containsKey(focusPkg)) focusPkg = null;
        travel = 0;
        scroll.smoothScrollTo(0, 0);
    }

    private Switcher switcher;

    /** Apps in use: switch to one, or close it. */
    private void showSwitcher() {
        if (switcher != null) return;
        if (options != null) options.dismiss();
        View back = getCurrentFocus();
        FrameLayout root = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);
        switcher = new Switcher(this, apps, a -> { hideSwitcher(back); launchApp(a); }, () -> hideSwitcher(back));
        root.addView(switcher, new FrameLayout.LayoutParams(-1, -1));
        switcher.takeFocus();
    }

    private void hideSwitcher(View back) {
        if (switcher == null) return;
        switcher.dismiss();
        switcher = null;
        if (back != null && back.isAttachedToWindow()) back.requestFocus();
        else restoreFocus();
    }

    @Override
    public void onBackPressed() {
        if (switcher != null) { hideSwitcher(null); return; }
        // Home has nowhere to go back to; Back from the rows returns to the apps.
        if (scroll.getScrollY() > 0 && !appCards.isEmpty()) appCards.get(0).requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            sky.hold();
            long now = android.os.SystemClock.uptimeMillis();
            // Held key or quick taps: focus moves get a light, quick animation (see liftable).
            fast = e.getRepeatCount() > 0 || now - lastKeyAt < 260;
            lastKeyAt = now;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: travel = -1; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: travel = 1; break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: travel = 0; break;
            }
        }
        // Menu (the ≡ key): options for the focused app or title; anywhere else, apps in use.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && switcher == null) {
            View f = getCurrentFocus();
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (f != null && f.isLongClickable()) f.performLongClick();
                else showSwitcher();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Pinned order: what the person arranged, else config.xml's dock_apps. */
    static List<String> dockOrder(Context c) {
        String saved = c.getSharedPreferences("home", MODE_PRIVATE).getString("dock", null);
        return new ArrayList<>(Arrays.asList(saved != null ? saved.split(",") : c.getResources().getStringArray(R.array.dock_apps)));
    }

    private void restoreFocus() {
        View v = card(focusPkg);
        if (v == null && !appCards.isEmpty()) v = appCards.get(0);
        if (v != null) v.requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        // No background of its own: the opaque sky covers the screen (one less full-screen pass).
        getWindow().setBackgroundDrawable(null);
        sky = new SkyView(this);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        hero = new HeroView(this);
        hero.setPanel(HeroView.PANEL);
        root.addView(hero, new FrameLayout.LayoutParams(-1, -1));
        // Dims the backdrop while browsing the rows below, so the cards carry the screen.
        dim = new View(this);
        dim.setBackgroundColor(0xB3000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout page = new FrameLayout(this);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        content.addView(page, new LinearLayout.LayoutParams(-1, getResources().getDisplayMetrics().heightPixels));

        bar = new Look.TopBar(this);
        page.addView(bar.view, bar.lp(this));
        nav = buildNav();
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        np.setMargins(0, dp(Look.PAD_TOP + 4), dp(Look.PAD_X), 0);
        page.addView(nav, np);
        navTip = Look.text(this, Look.sans(this, 600), 12, Color.WHITE);
        navTip.setBackground(new Look.Glass(dp(12), 0.95f));
        navTip.setPadding(dp(12), dp(6), dp(12), dp(6));
        navTip.setAlpha(0f);
        page.addView(navTip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(500), -2, Gravity.TOP | Gravity.START);
        ip.setMargins(dp(Look.PAD_X), dp(Look.HERO_TOP), 0, 0);
        page.addView(buildInfo(), ip);

        RowScroll appsScroll = new RowScroll(this, dp(Look.PAD_X));
        appsRow = row();
        appsScroll.addView(appsRow);
        page.addView(appsScroll, Look.appsLp(this));

        appTitle = Look.text(this, Look.serif(this), 44, Color.WHITE);
        appTitle.setSingleLine(true);
        appTitle.setShadowLayer(dp(16), 0, dp(1), 0x66000000);
        appTitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tp.setMargins(dp(Look.PAD_X), 0, 0, dp(176));
        page.addView(appTitle, tp);

        sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setClipChildren(false);
        sections.setClipToPadding(false);
        content.addView(sections, llp(-1, -2, 0, 0, 0, dp(120)));
        return root;
    }

    /** Featured title: why it's here, its name, a few facts, two lines of synopsis, then Open. */
    private View buildInfo() {
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setClipChildren(false);
        info.setClipToPadding(false);

        heroLabel = Look.text(this, Look.sans(this, 600), 13, 0x99FFFFFF);
        heroTitle = Look.text(this, Look.serif(this), 42, Color.WHITE);
        // Fixed geometry so the button and dots never move between titles: one line, long
        // names shrink to fit; the synopsis always takes exactly two lines.
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setAutoSizeTextTypeUniformWithConfiguration(26, 42, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        heroLabel.setSingleLine(true);
        chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setMinimumHeight(dp(18));
        heroOverview = Look.text(this, Look.sans(this, 400), 14, 0xB3FFFFFF);
        heroOverview.setLines(2);
        heroOverview.setEllipsize(TextUtils.TruncateAt.END);
        heroOverview.setLineSpacing(dp(4), 1f);
        for (TextView t : new TextView[]{heroLabel, heroTitle, heroOverview}) t.setShadowLayer(dp(14), 0, dp(1), 0x80000000);

        info.addView(heroLabel);
        info.addView(heroTitle, llp(-1, dp(54), 0, dp(4), 0, 0));
        info.addView(chips, llp(-2, -2, 0, dp(10), 0, 0));
        info.addView(heroOverview, llp(dp(380), -2, 0, dp(10), 0, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setClipChildren(false);
        heroButton = Look.text(this, Look.sans(this, 600), 14, Color.WHITE);
        heroButton.setGravity(Gravity.CENTER_VERTICAL);
        heroButton.setPadding(dp(16), 0, dp(20), 0);
        playIcon = new Look.Icon(Look.Icon.PLAY, dp(13));
        playIcon.setColor(Color.WHITE);
        heroButton.setCompoundDrawables(playIcon, null, null, null);
        heroButton.setCompoundDrawablePadding(dp(9));
        heroButton.setFocusable(true);
        heroButton.setId(View.generateViewId());
        Look.Glass glass = new Look.Glass(dp(19), 1.1f);
        GradientDrawable solid = new GradientDrawable();
        solid.setCornerRadius(dp(19));
        solid.setColor(0xF2FFFFFF);
        heroButton.setBackground(glass);
        heroButton.setOnFocusChangeListener((v, has) -> {
            heroButton.setBackground(has ? solid : glass);
            heroButton.setTextColor(has ? 0xFF16161C : Color.WHITE);
            playIcon.setColor(has ? 0xFF16161C : Color.WHITE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(ms(260)).start();
            if (has) { scroll.smoothScrollTo(0, 0); dimTo(0f); }
        });
        heroButton.setOnClickListener(v -> openHero());
        // Left and right on Open page through the featured titles.
        heroButton.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN || heroItems.size() < 2) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { showHero(heroIndex + 1, 1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { showHero(heroIndex - 1, -1); return true; }
            return false;
        });
        actions.addView(heroButton, new LinearLayout.LayoutParams(-2, dp(38)));
        dots = new Dots(this);
        actions.addView(dots, llp(dp(180), dp(8), dp(20), 0, 0, 0));
        info.addView(actions, llp(-2, -2, 0, dp(18), 0, 0));
        return info;
    }

    /** Glass capsule with Wi-Fi and Settings; a white puck slides to whichever is focused. */
    private View buildNav() {
        FrameLayout capsule = new FrameLayout(this);
        capsule.setBackground(new Look.Glass(dp(22), 0.9f));
        int pad = dp(4);
        capsule.setPadding(pad, pad, pad, pad);

        puck = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(18));
        pg.setColor(0xF2FFFFFF);
        puck.setBackground(pg);
        puck.setAlpha(0f);
        capsule.addView(puck, new FrameLayout.LayoutParams(dp(40), dp(36)));

        LinearLayout row = new LinearLayout(this);
        capsule.addView(row, new FrameLayout.LayoutParams(-2, dp(36)));
        wifiIcon = new Look.Icon(Look.Icon.WIFI, dp(15));
        TextView search = navItem("Search", new Look.Icon(Look.Icon.SEARCH, dp(15)), () -> startActivity(new Intent(this, Search.class)));
        TextView recents = navItem("Apps in use", new Look.Icon(Look.Icon.RECENTS, dp(15)), this::showSwitcher);
        TextView lightsItem = navItem("Lights", new Look.Icon(Look.Icon.BULB, dp(15)), () -> startActivity(new Intent(this, LightsActivity.class)));
        wifiItem = navItem("Wi-Fi", wifiIcon, () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView[] settings = new TextView[1];
        settings[0] = navItem("Settings", new Look.Icon(Look.Icon.GEAR, dp(15)), () -> showSettings(settings[0]));
        row.addView(search, new LinearLayout.LayoutParams(-2, -1));
        row.addView(recents, new LinearLayout.LayoutParams(-2, -1));
        row.addView(lightsItem, new LinearLayout.LayoutParams(-2, -1));
        row.addView(wifiItem, new LinearLayout.LayoutParams(-2, -1));
        row.addView(settings[0], new LinearLayout.LayoutParams(-2, -1));
        return capsule;
    }

    private TextView navItem(String label, Look.Icon icon, Runnable action) {
        TextView t = Look.text(this, Look.sans(this, 600), 13, 0xE6FFFFFF);
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), 0, dp(16), 0);
        icon.setColor(0xE6FFFFFF);
        t.setCompoundDrawables(icon, null, null, null);
        t.setCompoundDrawablePadding(dp(8));
        t.setFocusable(true);
        t.setId(View.generateViewId());
        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, has) -> {
            int c = has ? 0xFF16161C : 0xE6FFFFFF;
            t.setTextColor(c);
            icon.setColor(c);
            if (has) {
                movePuck(t);
                showTip(t);
                scroll.smoothScrollTo(0, 0);
            } else {
                // Fade the puck only when focus leaves the capsule, not when it moves inside it.
                handler.post(() -> {
                    if (navItems.contains(getCurrentFocus())) return;
                    puck.animate().alpha(0f).setDuration(ms(180)).start();
                    navTip.animate().alpha(0f).translationY(navTip.getTranslationY() - dp(4)).setDuration(ms(160)).start();
                });
            }
        });
        navItems.add(t);
        navLabels.put(t, label);
        return t;
    }

    /** Icons only: the focused button's name in a small glass tag under it, gliding along with focus. */
    private void showTip(TextView t) {
        if (!iconsOnly) return;
        navTip.setText(navLabels.get(t));
        navTip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        View row = (View) t.getParent();
        // Centred under the button, but not past the capsule's right end (the screen margin).
        float x = Math.min(nav.getRight() - navTip.getMeasuredWidth(),
                nav.getLeft() + row.getLeft() + t.getLeft() + t.getWidth() / 2f - navTip.getMeasuredWidth() / 2f);
        float y = nav.getBottom() + dp(8);
        boolean fresh = navTip.getAlpha() < 0.05f;
        if (fresh) navTip.setTranslationX(x);
        if (fresh) navTip.setTranslationY(y - dp(4));
        navTip.animate().cancel();
        navTip.animate().translationX(x).translationY(y).alpha(1f).setInterpolator(new DecelerateInterpolator(2f))
                .setDuration(ms(fresh ? 200 : 300)).start();
    }

    private void applyNavStyle() {
        iconsOnly = HomeSettings.get(this, HomeSettings.NAV) == 1;
        for (Map.Entry<TextView, String> e : navLabels.entrySet()) {
            TextView t = e.getKey();
            t.setText(iconsOnly ? "" : e.getValue());
            t.setCompoundDrawablePadding(iconsOnly ? 0 : dp(8));
            t.setPadding(dp(iconsOnly ? 12 : 14), 0, dp(iconsOnly ? 12 : 16), 0);
        }
        if (!iconsOnly) navTip.setAlpha(0f);
    }

    private void movePuck(View item) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) puck.getLayoutParams();
        boolean hiddenNow = puck.getAlpha() < 0.05f;
        float fromX = hiddenNow ? item.getLeft() : puck.getTranslationX();
        int fromW = hiddenNow ? item.getWidth() : p.width;
        float toX = item.getLeft();
        int toW = item.getWidth();
        if (puckAnim != null) puckAnim.cancel();
        puckAnim = ValueAnimator.ofFloat(0f, 1f);
        puckAnim.setDuration(ms(360));
        puckAnim.setInterpolator(new OvershootInterpolator(1.2f));
        puckAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            puck.setTranslationX(fromX + (toX - fromX) * f);
            p.width = Math.max(1, Math.round(fromW + (toW - fromW) * f));
            puck.setLayoutParams(p);
        });
        puckAnim.start();
        if (hiddenNow) {
            puck.setScaleX(0.85f);
            puck.setScaleY(0.85f);
        }
        puck.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms(220)).start();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(dp(Look.PAD_X), dp(Look.ROW_LIFT), dp(Look.PAD_X), dp(Look.ROW_LIFT + 8));
        return r;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }
        apps.clear();
        apps.putAll(byPkg);

        // Pinned first in the person's order, then the rest A to Z.
        List<App> order = new ArrayList<>();
        for (String p : dockPkgs) if (byPkg.containsKey(p)) order.add(byPkg.remove(p));
        dockCount = order.size();
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        order.addAll(rest);

        // Fire OS sends package broadcasts around app activity; a rebuild then must not pull focus
        // out of whatever has it (Apps in use, a row below) back to the first app.
        boolean refocus = appsRow.hasFocus() || getCurrentFocus() == null;
        appsRow.removeAllViews();
        appCards.clear();
        for (int i = 0; i < order.size(); i++) appsRow.addView(appTile(order.get(i)), Look.tileLp(this, Look.APP_W, i == 0));
        if (!order.isEmpty() && accent == 0xFF3A3A3A) {
            accent = order.get(0).color;
            sky.setAccent(accent, 0.6f, false);
        }
        wireFocus();
        if (refocus) restoreFocus();
    }

    private View appTile(App app) {
        FrameLayout card = new AspectFrame(this);
        card.setTag(app);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C22 : darken(app.color, 0.45f));
        card.setBackground(bg);
        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        img.setScaleType(app.isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!app.isBanner) img.setPadding(dp(22), dp(14), dp(22), dp(14));
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View.OnFocusChangeListener lift = liftable(card, app.color, null);
        card.setOnClickListener(v -> launchApp(app));
        card.setOnLongClickListener(v -> { showOptions(v); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(v, has);
            if (!has) return;
            focusPkg = app.pkg;
            accent = app.color;
            Lights.homeColor(this, app.color);
            sky.setAccent(app.color, 0.6f, true);
            dimTo(0f);
            scroll.smoothScrollTo(0, 0);
            // Narrow the hero to this app once focus rests here, not while sweeping past.
            if (pendingSource != null) handler.removeCallbacks(pendingSource);
            if (minimal) { setAppTitle(app.label); return; }
            pendingSource = () -> setHeroSource(app);
            handler.postDelayed(pendingSource, 700);
        });
        appCards.add(card);
        return card;
    }

    /**
     * Focus look shared by every card: lifts with a shadow in {@code color}, a streak of light runs
     * across, the rim brightens, and {@code label} (if any) fades in under it. Returns the
     * listener so callers can add to it. (A 3D swing was tried: it doubled dropped frames here.)
     */
    private View.OnFocusChangeListener liftable(FrameLayout card, int color, TextView label) {
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        Look.roundClip(card, dp(Look.TILE_RADIUS));
        card.setOutlineSpotShadowColor(color);
        card.setOutlineAmbientShadowColor(color);

        View streak = new View(this);
        streak.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x00FFFFFF, 0x47FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        streak.setRotation(18f);
        streak.setScaleY(1.8f);
        streak.setAlpha(0f);
        card.addView(streak, new FrameLayout.LayoutParams(-1, -1));
        View rim = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(dp(Look.TILE_RADIUS));
        rg.setStroke(dp(1.5f), 0x2EFFFFFF);
        rim.setBackground(rg);
        card.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        View focusRim = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setCornerRadius(dp(Look.TILE_RADIUS));
        fg.setStroke(dp(2), 0xB3FFFFFF);
        focusRim.setBackground(fg);
        focusRim.setAlpha(0f);
        card.addView(focusRim, new FrameLayout.LayoutParams(-1, -1));

        return (v, has) -> {
            if (has && (fast || calm)) {
                // Sweeping along a row: settle quickly and skip the light streak, so focus keeps
                // up with the key instead of a queue of springy animations trailing behind it.
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(dp(16))
                        .setInterpolator(new DecelerateInterpolator(2f)).setDuration(ms(150)).start();
                focusRim.animate().alpha(1f).setDuration(ms(100)).start();
                if (label != null) label.animate().alpha(1f).translationY(dp(4)).setDuration(ms(120)).start();
            } else if (has) {
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(dp(16))
                        .setInterpolator(new OvershootInterpolator(1.5f)).setDuration(ms(460)).start();
                focusRim.animate().alpha(1f).setDuration(ms(200)).start();
                float w = v.getWidth();
                streak.animate().cancel();
                streak.setTranslationX(-w * 1.1f);
                streak.setAlpha(1f);
                streak.animate().translationX(w * 1.1f).setStartDelay(ms(90)).setDuration(ms(900))
                        .setInterpolator(new DecelerateInterpolator(1.4f))
                        .withEndAction(() -> streak.setAlpha(0f)).start();
                if (label != null) label.animate().alpha(1f).translationY(dp(4)).setDuration(ms(220)).start();
            } else {
                v.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                        .setInterpolator(new DecelerateInterpolator(2f)).setDuration(ms(fast ? 140 : 260)).start();
                focusRim.animate().alpha(0f).setDuration(ms(180)).start();
                if (label != null) label.animate().alpha(0f).translationY(0).setDuration(ms(180)).start();
            }
        };
    }

    // ---------------------------------------------------------------- content

    /** Trending titles, weather and recently opened, off the main thread; cached where it can be. */
    private void loadContent() {
        io.execute(() -> {
            String w = Weather.line(this);
            runOnUiThread(() -> bar.weather.setText(w)); // hidden by applySettings if turned off
            try {
                JSONArray r = Catalog.recs(this), recent = Catalog.recent(this);
                runOnUiThread(() -> setContent(r, recent));
            } catch (Exception ignored) { }
        });
    }

    private void setContent(JSONArray r, JSONArray recent) {
        if (isDestroyed()) return;
        boolean first = recs.length() == 0;
        recs = r;
        buildSections(recent);
        if (first) setHeroSource(heroSource == null ? null : apps.get(heroSource));
    }

    /** Hero shows {@code app}'s trending titles (everything when null); if it has none, all of them. */
    private void setHeroSource(App app) {
        String src = app == null ? null : app.pkg;
        if (src != null && src.equals(heroSource) && !heroItems.isEmpty()) return;
        List<JSONObject> list = titlesOn(src, 12);
        heroSource = src;
        heroApp = list.isEmpty() ? app : null;
        heroItems.clear();
        heroItems.addAll(list.isEmpty() ? titlesOn(null, 12) : list);
        showHero(0, travel == 0 ? 1 : travel);
    }

    private List<JSONObject> titlesOn(String pkg, int max) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < recs.length() && out.size() < max; i++) {
            JSONObject t = recs.optJSONObject(i);
            if (t != null && offerFor(t, pkg) != null) out.add(t);
        }
        return out;
    }

    private static JSONObject offerFor(JSONObject t, String pkg) {
        JSONArray o = t.optJSONArray("offers");
        for (int i = 0; o != null && i < o.length(); i++) {
            JSONObject x = o.optJSONObject(i);
            if (x != null && (pkg == null || pkg.equals(x.optString("pkg")))) return x;
        }
        return null;
    }

    /** Show hero item {@code i} (wraps), sliding the text in from {@code dir}. */
    private void showHero(int i, int dir) {
        if (minimal) return;
        scheduleAdvance();
        if (heroItems.isEmpty()) {
            // Nothing to feature (offline or first run): name the focused app over the sky.
            App a = heroApp != null ? heroApp : heroSource == null ? null : apps.get(heroSource);
            swapInfo(dir, () -> {
                heroLabel.setText("");
                heroTitle.setText(a == null ? "" : a.label);
                chips.removeAllViews();
                heroOverview.setText("");
                heroButton.setText(a == null ? "" : "Open " + a.label);
                heroButton.setVisibility(a == null ? View.INVISIBLE : View.VISIBLE);
                dots.set(0, 0);
            });
            hero.setImage(null);
            sky.setGrain(true);
            showSky(true);
            return;
        }
        heroIndex = (i % heroItems.size() + heroItems.size()) % heroItems.size();
        JSONObject t = heroItems.get(heroIndex);
        JSONObject offer = offerFor(t, heroApp == null ? heroSource : null);
        if (offer == null) offer = offerFor(t, null);
        String appLabel = offer == null ? "" : offer.optString("label");
        String url = t.optString("backdrop", null);
        long token = ++heroToken;
        // Text and picture change together: the swap waits for the picture (usually prepared
        // already, see prepareNext), and the picture goes up the moment the new text does.
        java.util.function.Consumer<Bitmap> swap = b -> swapInfo(dir, () -> {
            heroLabel.setText(heroApp != null ? "Trending elsewhere, nothing new on " + heroApp.label
                    : "Trending on " + appLabel);
            heroTitle.setText(t.optString("title"));
            fillChips(t);
            heroOverview.setText(t.optString("overview"));
            heroButton.setText("Open in " + appLabel);
            heroButton.setVisibility(View.VISIBLE);
            dots.set(heroItems.size(), heroIndex);
            if (b != null) { hero.setImage(b); sky.setGrain(false); }
            prepareNext();
        });
        if (url == null || url.equals(preparedUrl)) {
            Bitmap b = url == null ? null : prepared;
            prepared = null;
            preparedUrl = null;
            swap.accept(b);
            return;
        }
        io.execute(() -> {
            Bitmap b = bakeHero(url);
            runOnUiThread(() -> {
                if (token != heroToken || isDestroyed()) { if (b != null) b.recycle(); return; }
                swap.accept(b);
            });
        });
    }

    private long heroToken;
    private String preparedUrl;
    private Bitmap prepared;

    /** Downloaded (or cached), cropped, faded and uploaded: ready for HeroView. Off the main thread. */
    private Bitmap bakeHero(String url) {
        Bitmap src = Net.image(this, url, 700);
        Bitmap b = src == null ? null : HeroView.bakeRight(src, heroW);
        if (src != null) src.recycle();
        if (b != null) b.prepareToDraw();
        return b;
    }

    /** Have the next featured picture ready, so paging (and the auto advance) swaps at once. */
    private void prepareNext() {
        if (heroItems.size() < 2) return;
        String url = heroItems.get((heroIndex + 1) % heroItems.size()).optString("backdrop", null);
        if (url == null || url.equals(preparedUrl)) return;
        io.execute(() -> {
            Bitmap b = bakeHero(url);
            runOnUiThread(() -> {
                if (b == null || isDestroyed()) return;
                if (prepared != null) prepared.recycle();
                prepared = b;
                preparedUrl = url;
            });
        });
    }

    private void fillChips(JSONObject t) {
        chips.removeAllViews();
        List<String> facts = new ArrayList<>();
        facts.add("tv".equals(t.optString("kind")) ? "Series" : "Movie");
        if (t.optInt("year") > 0) facts.add(String.valueOf(t.optInt("year")));
        JSONArray g = t.optJSONArray("genres");
        if (g != null && g.length() > 0) facts.add(g.optString(0));
        if (t.has("rating")) facts.add("IMDb " + t.optDouble("rating"));
        // One quiet line; facts spaced apart with small dots, rating set off in white.
        for (int k = 0; k < facts.size(); k++) {
            if (k > 0) {
                View dot = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(0x66FFFFFF);
                dot.setBackground(d);
                chips.addView(dot, llp(dp(3), dp(3), dp(9), 0, dp(9), 0));
            }
            boolean rating = facts.get(k).startsWith("IMDb");
            TextView c = Look.text(this, Look.sans(this, rating ? 600 : 500), 13, rating ? 0xE6FFFFFF : 0x99FFFFFF);
            c.setText(facts.get(k));
            chips.addView(c);
        }
    }

    /** Text out (sliding against {@code dir}), update, text in (from {@code dir}), staggered. */
    private void swapInfo(int dir, Runnable update) {
        float off = dp(24) * (dir == 0 ? 1 : dir);
        View[] parts = {heroLabel, heroTitle, chips, heroOverview};
        for (View v : parts) v.animate().cancel();
        for (int k = 1; k < parts.length; k++) {
            parts[k].animate().alpha(0f).translationX(-off).setStartDelay(0).setDuration(ms(140))
                    .setInterpolator(new AccelerateInterpolator()).start();
        }
        heroLabel.animate().alpha(0f).translationX(-off).setStartDelay(0).setDuration(ms(140))
                .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                    update.run();
                    for (int k = 0; k < parts.length; k++) {
                        View v = parts[k];
                        v.setTranslationX(off);
                        v.animate().alpha(1f).translationX(0f).setStartDelay(ms(40L * k)).setDuration(ms(420))
                                .setInterpolator(new DecelerateInterpolator(2.2f)).start();
                    }
                }).start();
    }

    /** Read HomeSettings and rearrange: style, background, weather, motion; rows on next load. */
    private void applySettings() {
        appliedSettings = HomeSettings.version(this);
        Screensaver.apply(this);
        minimal = HomeSettings.get(this, HomeSettings.STYLE) == HomeSettings.STYLE_MINIMAL;
        calm = HomeSettings.get(this, HomeSettings.MOTION) == 1;
        sky.setLook(HomeSettings.get(this, HomeSettings.LOOK));
        boolean photo = HomeSettings.get(this, HomeSettings.LOOK) == SkyView.WALLPAPER;
        bar.onPhoto(this, photo);
        appTitle.setShadowLayer(dp(photo ? 8 : 16), 0, dp(1), photo ? 0xCC000000 : 0x66000000);
        bar.weather.setVisibility(HomeSettings.get(this, HomeSettings.WEATHER) == 1 ? View.VISIBLE : View.GONE);
        applyNavStyle();
        info.setVisibility(minimal ? View.GONE : View.VISIBLE);
        appTitle.setVisibility(minimal ? View.VISIBLE : View.GONE);
        if (minimal) {
            handler.removeCallbacks(advance);
            hero.setImage(null);
            View f = getCurrentFocus();
            App a = f != null && f.getTag() instanceof App ? (App) f.getTag() : appCards.isEmpty() ? null : (App) appCards.get(0).getTag();
            appTitle.setText(a == null ? "" : a.label);
        } else if (!heroItems.isEmpty()) {
            showHero(heroIndex, 0);
        }
        wireFocus();
    }

    /** Minimal mode's big name: slides the way focus moved. */
    private void setAppTitle(String label) {
        if (label.contentEquals(appTitle.getText())) return;
        float off = dp(20) * travel;
        appTitle.animate().cancel();
        appTitle.animate().alpha(0f).translationX(-off).setStartDelay(0).setDuration(ms(fast ? 60 : 110))
                .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                    appTitle.setText(label);
                    appTitle.setTranslationX(off);
                    appTitle.animate().alpha(1f).translationX(0f).setDuration(ms(fast ? 140 : 300))
                            .setInterpolator(new DecelerateInterpolator(2f)).start();
                }).start();
    }

    private void scheduleAdvance() {
        handler.removeCallbacks(advance);
        handler.postDelayed(advance, 9000);
    }

    /** Page on its own only while nobody is reading or using the hero. */
    private boolean canAdvance() {
        return !minimal && heroItems.size() > 1 && hasWindowFocus() && getCurrentFocus() != heroButton && scroll.getScrollY() == 0;
    }

    private void openHero() {
        if (heroItems.isEmpty()) {
            App a = heroApp != null ? heroApp : heroSource == null ? null : apps.get(heroSource);
            if (a != null) launchApp(a);
            return;
        }
        openTitle(heroItems.get(heroIndex), heroApp == null ? heroSource : null);
    }

    private void openTitle(JSONObject t, String preferPkg) {
        JSONObject offer = offerFor(t, preferPkg);
        if (offer == null) offer = offerFor(t, null);
        if (offer == null) return;
        String pkg = offer.optString("pkg");
        if (Catalog.open(this, offer)) io.execute(() -> {
            try { Catalog.remember(this, new JSONObject(t.toString()), pkg); } catch (Exception ignored) { }
        });
    }

    // ---------------------------------------------------------------- rows below

    /** "Recently opened", then a row per app (pinned order) with what's trending on it. */
    private void buildSections(JSONArray recent) {
        // Rebuilt under focus (the offline banner going away, say): put it back on a card after.
        boolean refocus = sections.hasFocus();
        sections.removeAllViews();
        sectionCards.clear();
        artColors.clear();
        rowLoaders.clear();
        rowImages.clear();
        rowsLoaded.clear();
        List<JSONObject> rec = new ArrayList<>();
        for (int i = 0; i < recent.length(); i++) {
            JSONObject t = recent.optJSONObject(i);
            if (t != null) rec.add(t);
        }
        if (!online) addOfflineBanner();
        if (!rec.isEmpty() && HomeSettings.get(this, HomeSettings.RECENT) == 1) addSection("Recently watched", rec, null);
        List<String> order = new ArrayList<>(dockPkgs);
        for (String p : apps.keySet()) if (!order.contains(p)) order.add(p);
        if (HomeSettings.get(this, HomeSettings.TRENDING) == 0) order.clear();
        for (String pkg : order) {
            App a = apps.get(pkg);
            List<JSONObject> list = titlesOn(pkg, 10);
            if (a != null && list.size() >= 3) addSection("Trending on " + a.label, list, pkg);
        }
        wireFocus();
        // The first two rows of pictures; the offline banner, when up, is a row of its own above them.
        if (resumed) loadRowsAround(online ? 0 : 1);
        if (refocus) {
            View v = card(focusPkg);
            if (v == null && !sectionCards.isEmpty()) v = sectionCards.get(0).get(0);
            if (v != null) v.requestFocus(); else restoreFocus();
        }
    }

    private void addSection(String title, List<JSONObject> items, String pkg) {
        TextView label = Look.text(this, Look.sans(this, 600), 15, 0xCCFFFFFF);
        label.setText(title);
        sections.addView(label, llp(-2, -2, dp(Look.PAD_X + 2), dp(12), 0, 0));
        RowScroll rs = new RowScroll(this, dp(Look.PAD_X));
        LinearLayout r = row();
        // Just enough room for the focus lift; rows below the apps sit close together. Poster rows
        // are taller, so they get a little more air.
        r.setPadding(dp(Look.PAD_X), dp(12), dp(Look.PAD_X), dp(pkg == null ? 6 : 18));
        rs.addView(r);
        List<View> cards = new ArrayList<>();
        rowLoaders.add(new ArrayList<>());
        rowImages.add(new ArrayList<>());
        for (int i = 0; i < items.size(); i++) {
            if (pkg == null) r.addView(titleCard(items.get(i), null, cards, rs), Look.tileLp(this, Look.APP_W, i == 0));
            else rankCard(r, items.get(i), i + 1, pkg, cards, rs);
        }
        sections.addView(rs, new LinearLayout.LayoutParams(-1, -2));
        sectionCards.add(cards);
    }

    /**
     * A title as a 16:9 card the same size as the app tiles, so every row lines up: its picture,
     * darkened at the foot, with the name (and for history, the app and episode) written on it.
     */
    private View titleCard(JSONObject t, String pkg, List<View> cards, View rowScroll) {
        FrameLayout card = new AspectFrame(this);
        card.setTag(t.optString("id"));
        JSONObject offer = offerFor(t, pkg != null ? pkg : t.optString("watchedPkg", null));
        if (offer == null) offer = offerFor(t, null);
        App a = offer == null ? null : apps.get(offer.optString("pkg"));
        boolean history = pkg == null;
        // Until the picture arrives (or if there's none): the app's colour, deepening to near black.
        card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{a != null ? darken(a.color, 0.55f) : 0xFF2A2A33, 0xFF121217}));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View foot = new View(this);
        foot.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0D000000, 0x40000000, 0xCC000000, 0xF7000000}));
        card.addView(foot, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(11), 0, dp(11), dp(9));
        TextView name = Look.text(this, Look.serif(this), 16, Color.WHITE);
        name.setText(t.optString("title"));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        words.addView(name);
        String watchedOn = t.optString("watchedPkg", null);
        String ep = t.optString("episode", "");
        App w = watchedOn == null ? null : apps.get(watchedOn);
        String sub = history ? ((w != null ? w.label : "") + (ep.isEmpty() ? "" : ", " + ep))
                : (t.optInt("year") > 0 ? String.valueOf(t.optInt("year")) : "");
        if (!sub.isEmpty()) {
            TextView s2 = Look.text(this, Look.sans(this, 500), 10, 0xA6FFFFFF);
            s2.setText(sub);
            s2.setSingleLine(true);
            s2.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(s2, llp(-1, -2, 0, dp(2), 0, 0));
        }
        card.addView(words, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        View.OnFocusChangeListener lift = liftable(card, a != null ? a.color : accent, null);
        String prefer = pkg != null ? pkg : watchedOn;
        card.setOnClickListener(v -> openTitle(t, prefer));
        if (history) card.setOnLongClickListener(v -> { showHistoryOptions(v, t, prefer); return true; });
        else card.setLongClickable(false);
        card.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(v, has);
            if (!has) return;
            focusPkg = t.optString("id");
            Integer art = artColors.get(card);
            Lights.homeColor(this, art != null ? art : a != null ? a.color : accent);
            dimTo(1f);
            loadRowsAround(sectionCards.indexOf(cards));
            int y = sections.getTop() + rowScroll.getTop() - dp(96);
            int max = Math.max(0, content.getHeight() - scroll.getHeight());
            scroll.smoothScrollTo(0, Math.max(0, Math.min(y, max)));
        });
        String url = t.optString("thumb", null);
        int row = rowLoaders.size() - 1;
        rowImages.get(row).add(img);
        rowLoaders.get(row).add(() -> io.execute(() -> {
            Bitmap b = Net.image(this, url, 400);
            int art = b == null ? 0 : Look.sampleColor(new android.graphics.drawable.BitmapDrawable(getResources(), b));
            if (b != null) runOnUiThread(() -> {
                if (rowsLoaded.contains(row)) img.setImageBitmap(b);
                artColor(card, art);
            });
        }));
        cards.add(card);
        return card;
    }

    private static final int RANK_POSTER_W = 96;

    /**
     * A trending title as a ranked poster, like a streaming app's Top 10: its rank as a big serif
     * numeral, half behind the poster's left edge. The numeral warms to the app's colour on focus.
     * The whole unit takes focus, so scrolling to it keeps the numeral in view.
     */
    private void rankCard(LinearLayout row, JSONObject t, int rank, String pkg, List<View> cards, View rowScroll) {
        App a = apps.get(pkg);
        int color = a != null ? a.color : accent;
        int pw = dp(RANK_POSTER_W), ph = pw * 3 / 2;
        // Numerals a little shorter than the poster and only tucked under its edge, so each pair
        // reads as one item with air either side.
        RankNumber num = new RankNumber(this, rank, color, ph * 4 / 5);
        int overlap = (int) (num.wide * 0.18f);
        FrameLayout unit = new FrameLayout(this);
        unit.setClipChildren(false);
        unit.setTag(t.optString("id"));
        unit.addView(num, new FrameLayout.LayoutParams(num.wide, ph));

        FrameLayout poster = new FrameLayout(this);
        poster.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{darken(color, 0.55f), 0xFF121217}));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.addView(img, new FrameLayout.LayoutParams(-1, -1));
        String url = t.optString("poster", "");
        if (url.isEmpty()) {
            // No poster: the backdrop, with the name written on it like the other cards.
            url = t.optString("thumb", null);
            TextView name = Look.text(this, Look.serif(this), 15, Color.WHITE);
            name.setText(t.optString("title"));
            name.setMaxLines(3);
            name.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
            name.setPadding(dp(10), 0, dp(10), dp(10));
            poster.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }
        View.OnFocusChangeListener lift = liftable(poster, color, null);
        poster.setFocusable(false);
        poster.setClickable(false);
        poster.setLongClickable(false);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(pw, ph);
        pp.leftMargin = num.wide - overlap;
        unit.addView(poster, pp);

        unit.setFocusable(true);
        unit.setClickable(true);
        unit.setOnClickListener(v -> openTitle(t, pkg));
        unit.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(poster, has);
            num.glow(has, ms(has ? 420 : 260));
            if (!has) return;
            focusPkg = t.optString("id");
            Integer art = artColors.get(unit);
            Lights.homeColor(this, art != null ? art : color);
            dimTo(1f);
            loadRowsAround(sectionCards.indexOf(cards));
            int y = sections.getTop() + rowScroll.getTop() - dp(96);
            int max = Math.max(0, content.getHeight() - scroll.getHeight());
            scroll.smoothScrollTo(0, Math.max(0, Math.min(y, max)));
        });
        String src = url;
        int r = rowLoaders.size() - 1;
        rowImages.get(r).add(img);
        rowLoaders.get(r).add(() -> io.execute(() -> {
            Bitmap b = Net.image(this, src, 260);
            int art = b == null ? 0 : Look.sampleColor(new android.graphics.drawable.BitmapDrawable(getResources(), b));
            if (b != null) runOnUiThread(() -> {
                if (rowsLoaded.contains(r)) img.setImageBitmap(b);
                artColor(unit, art);
            });
        }));
        cards.add(unit);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(num.wide - overlap + pw, ph);
        lp.setMargins(rank == 1 ? 0 : dp(26), 0, 0, 0);
        row.addView(unit, lp);
    }

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        online = isOnline(cm);
        netWatch = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { recheck(); }
            @Override public void onLost(Network n) { recheck(); }
            private void recheck() {
                boolean now = isOnline(cm);
                if (now == online || !resumed) return;
                online = now;
                updateWifi();
                // Offline: the banner goes up over the saved rows. Back online: fresh rows and weather.
                loadContent();
            }
        };
        cm.registerDefaultNetworkCallback(netWatch, handler);
    }

    private static boolean isOnline(ConnectivityManager cm) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Offline: a glass card above the rows saying they're the saved ones and will refresh by
     * themselves, with a way to Wi-Fi settings. It's the first row, so Down from the apps finds it.
     */
    private void addOfflineBanner() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(new Look.Glass(dp(20), 0.9f));
        card.setPadding(dp(16), dp(14), dp(14), dp(14));

        FrameLayout badge = new FrameLayout(this);
        GradientDrawable disc = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0x66FFB24A, 0x1FFF7A45});
        disc.setShape(GradientDrawable.OVAL);
        badge.setBackground(disc);
        Look.Icon icon = new Look.Icon(Look.Icon.WIFI, dp(18));
        icon.dim = true;
        icon.setColor(0xFFFFC27A);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        badge.addView(iv, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        card.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));
        if (!calm) {
            // A slow breath on the badge, the only thing moving while offline.
            ValueAnimator breathe = ValueAnimator.ofFloat(0.55f, 1f).setDuration(1600);
            breathe.setRepeatMode(ValueAnimator.REVERSE);
            breathe.setRepeatCount(ValueAnimator.INFINITE);
            breathe.addUpdateListener(v -> badge.setAlpha((float) v.getAnimatedValue()));
            badge.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { breathe.start(); }
                @Override public void onViewDetachedFromWindow(View v) { breathe.cancel(); }
            });
        }

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = Look.text(this, Look.serif(this), 21, Color.WHITE);
        title.setText("You're offline");
        TextView sub = Look.text(this, Look.sans(this, 500), 12, 0x99FFFFFF);
        sub.setText("Showing titles saved earlier. Everything refreshes once the TV is back online.");
        words.addView(title);
        words.addView(sub, llp(-2, -2, 0, dp(1), 0, 0));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.setMargins(dp(14), 0, dp(14), 0);
        card.addView(words, wp);

        TextView wifi = optionRow("Wi-Fi settings", null);
        GradientDrawable idle = (GradientDrawable) wifi.getBackground();
        idle.setColor(0x1FFFFFFF);
        wifi.setOnFocusChangeListener((v, has) -> {
            idle.setColor(has ? 0xF2FFFFFF : 0x1FFFFFFF);
            wifi.setTextColor(has ? 0xFF16161C : Color.WHITE);
            if (!has) return;
            focusPkg = null;
            dimTo(1f);
            loadRowsAround(1);
            scroll.smoothScrollTo(0, Math.max(0, sections.getTop() - dp(96)));
        });
        wifi.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        card.addView(wifi, new LinearLayout.LayoutParams(-2, dp(36)));

        sections.addView(card, llp(-1, -2, dp(Look.PAD_X), dp(14), dp(Look.PAD_X), dp(4)));
        // A row of one, so the D-pad wiring treats it like the rows below.
        List<View> cards = new ArrayList<>();
        cards.add(wifi);
        sectionCards.add(cards);
        rowLoaders.add(new ArrayList<>());
        rowImages.add(new ArrayList<>());
    }

    /** Remember a card's picture colour; if it's focused already, the lights take it now. */
    private void artColor(View card, int color) {
        // sampleColor gives grey for pictures without a clear colour: keep the app's then.
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.1f) return;
        artColors.put(card, color);
        if (card.hasFocus()) Lights.homeColor(this, color);
    }

    /** Posters for row {@code s} and its neighbours; the first rows also load once content arrives. */
    private void loadRowsAround(int s) {
        for (int r = Math.max(0, s - 1); r <= s + 1 && r < rowLoaders.size(); r++) {
            if (!rowsLoaded.add(r)) continue;
            for (Runnable load : rowLoaders.get(r)) load.run();
        }
    }

    /** Away in an app: give the posters' memory back (they reload, from disk, on return). */
    private void unloadRows() {
        for (int r : rowsLoaded) for (ImageView v : rowImages.get(r)) v.setImageDrawable(null);
        rowsLoaded.clear();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unloadRows();
    }

    /** D-pad targets between the nav, the hero's Open button, the apps row and the rows below. */
    private void wireFocus() {
        if (appCards.isEmpty()) return;
        View firstApp = appCards.get(0);
        int heroId = heroButton.getId();
        for (int i = 0; i < navItems.size(); i++) {
            View p = navItems.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : navItems.get(i - 1).getId());
            p.setNextFocusRightId(i == navItems.size() - 1 ? p.getId() : navItems.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(heroId);
        }
        heroButton.setNextFocusUpId(navItems.get(0).getId());
        heroButton.setNextFocusDownId(id(firstApp));
        // Minimal: no Open button between the nav and the apps.
        if (minimal) {
            heroId = id(firstApp);
            for (View p : navItems) p.setNextFocusDownId(heroId);
            heroId = navItems.get(0).getId();
        }
        int below = sectionCards.isEmpty() || sectionCards.get(0).isEmpty() ? -1 : id(sectionCards.get(0).get(0));
        for (int i = 0; i < appCards.size(); i++) {
            View c = appCards.get(i);
            c.setNextFocusUpId(heroId);
            c.setNextFocusDownId(below == -1 ? id(c) : below);
            if (i == 0) c.setNextFocusLeftId(id(c));
            if (i == appCards.size() - 1) c.setNextFocusRightId(id(c));
        }
        for (int s = 0; s < sectionCards.size(); s++) {
            List<View> cards = sectionCards.get(s);
            for (int i = 0; i < cards.size(); i++) {
                View c = cards.get(i);
                c.setNextFocusUpId(s == 0 ? id(firstApp) : id(near(sectionCards.get(s - 1), i)));
                c.setNextFocusDownId(s == sectionCards.size() - 1 ? id(c) : id(near(sectionCards.get(s + 1), i)));
                if (i == 0) c.setNextFocusLeftId(id(c));
                if (i == cards.size() - 1) c.setNextFocusRightId(id(c));
            }
        }
    }

    private static View near(List<View> row, int i) {
        return row.get(Math.min(i, row.size() - 1));
    }

    private static int id(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        return v.getId();
    }

    /** The card for an app package or a title id. */
    private View card(String key) {
        if (key == null) return null;
        for (View c : appCards) if (key.equals(((App) c.getTag()).pkg)) return c;
        for (List<View> l : sectionCards) for (View c : l) if (key.equals(c.getTag())) return c;
        return null;
    }

    // ---------------------------------------------------------------- options

    /** Small sheet under an app: reorder or (un)pin, and app info. */
    private void showOptions(View card) {
        App app = (App) card.getTag();
        List<String> pinned = new ArrayList<>();
        for (String p : dockPkgs) if (apps.containsKey(p)) pinned.add(p);
        int i = pinned.indexOf(app.pkg);

        List<TextView> rows = new ArrayList<>();
        if (i >= 0) {
            if (i > 0) rows.add(optionRow("Move left", () -> moveInDock(pinned, i, -1)));
            if (i < pinned.size() - 1) rows.add(optionRow("Move right", () -> moveInDock(pinned, i, 1)));
            rows.add(optionRow("Unpin", () -> { pinned.remove(i); saveDock(pinned, app.pkg); }));
        } else {
            rows.add(optionRow("Pin to the front", () -> { pinned.add(app.pkg); saveDock(pinned, app.pkg); }));
        }
        rows.add(optionRow("App info", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.pkg)))));
        showSheet(card, rows, (card.getWidth() - dp(SHEET_W)) / 2);
    }

    /** Hold OK (or Menu) on a history card: open it, or take it off the row. */
    private void showHistoryOptions(View card, JSONObject t, String pkg) {
        JSONObject offer = offerFor(t, pkg);
        App a = offer == null ? null : apps.get(offer.optString("pkg"));
        List<TextView> rows = new ArrayList<>();
        if (a != null) rows.add(optionRow("Open in " + a.label, () -> openTitle(t, pkg)));
        rows.add(optionRow("Remove from Recently watched", () -> {
            Catalog.forget(this, t.optString("id"));
            focusPkg = null;
            loadContent();
        }));
        showSheet(card, rows, (card.getWidth() - dp(SHEET_W)) / 2);
    }

    // Fire OS 7 keeps Settings as a tab of the Amazon home (ACTION_SETTINGS lands there, and
    // HomeService would bounce it), so open sections directly where Fire OS allows it.
    private static final String[][] FIRE_SETTINGS = {
            {"Network", Settings.ACTION_WIFI_SETTINGS},
            {"Applications", Settings.ACTION_APPLICATION_SETTINGS},
            {"Remotes & Bluetooth", "android.settings.CONTROLLERS_SETTINGS"},
            {"My Fire TV", Settings.ACTION_DEVICE_INFO_SETTINGS},
    };

    private void showSettings(View anchor) {
        List<TextView> rows = new ArrayList<>();
        // Sections Fire OS opens to any app via public settings intents...
        // Fresh task each time, or Fire OS brings back whichever settings screen was open last.
        for (String[] s : FIRE_SETTINGS) rows.add(optionRow(s[0], () -> launch(new Intent(s[1])
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK))));
        // ...and the rest (display, sound, preferences, accessibility, account) via Amazon's tab.
        rows.add(optionRow("All settings", () -> HomeService.openAmazonSettings(this)));
        rows.add(0, optionRow("Customise home", () -> startActivity(new Intent(this, HomeSettings.class))));
        showSheet(anchor, rows, anchor.getWidth() - dp(SHEET_W));
    }

    private static final int SHEET_W = 260;

    /** Small dark sheet of rows under {@code anchor}; focus returns there when it closes. */
    private void showSheet(View anchor, List<TextView> rows, int xOff) {
        // The sheet opens where the top bar's name tag sits; the tag returns with focus.
        navTip.animate().cancel();
        navTip.setAlpha(0f);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        list.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xEB17171E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        PopupWindow pw = new PopupWindow(list, dp(SHEET_W), -2, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(46)));
        }
        for (int k = 0; k < rows.size(); k++) {
            TextView r = rows.get(k);
            r.setNextFocusUpId(rows.get(Math.max(0, k - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, k + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setElevation(dp(24));
        pw.setOnDismissListener(() -> { options = null; if (anchor.isAttachedToWindow()) anchor.requestFocus(); });
        options = pw;
        // Below the anchor when it fits, else above it (the apps row sits at the screen's foot).
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(SHEET_W), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int screenH = getResources().getDisplayMetrics().heightPixels, h = list.getMeasuredHeight();
        boolean above = at[1] + anchor.getHeight() + dp(16) + h > screenH - dp(12);
        pw.showAsDropDown(anchor, xOff, above ? -(anchor.getHeight() + h + dp(16)) : dp(16));
        list.setAlpha(0f);
        list.setScaleX(0.94f);
        list.setScaleY(0.94f);
        list.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms(200))
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        rows.get(0).requestFocus();
    }

    private TextView optionRow(String label, Runnable action) {
        TextView r = Look.text(this, Look.sans(this, 500), 16, Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), 0, dp(18), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF16161C : Color.WHITE);
        });
        return r;
    }

    private void moveInDock(List<String> pinned, int i, int delta) {
        Collections.swap(pinned, i, i + delta);
        travel = delta;
        saveDock(pinned, pinned.get(i + delta));
    }

    /** Persist the pinned order, rebuild, and keep focus on the app that was acted on. */
    private void saveDock(List<String> pinned, String focus) {
        dockPkgs.clear();
        dockPkgs.addAll(pinned);
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("dock", TextUtils.join(",", pinned)).apply();
        focusPkg = focus;
        buildApps();
    }

    // ---------------------------------------------------------------- status

    private void updateWifi() {
        String name = "Wi-Fi";
        boolean offline = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                offline = wm.getConnectionInfo().getNetworkId() == -1;
            }
        } catch (SecurityException ignored) { }
        String label = offline ? "Offline" : online ? name : "No internet";
        navLabels.put(wifiItem, label);
        wifiItem.setText(iconsOnly ? "" : label);
        wifiIcon.dim = offline || !online;
        wifiIcon.invalidateSelf();
    }

    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached == null) { cached = Look.sampleColor(d); colorCache.put(pkg, cached); }
        return cached;
    }

    // ---------------------------------------------------------------- entrance

    /** The one orchestrated moment: top settles, the featured title fades up, apps rise in turn. */
    private void prepareEntrance(boolean full) {
        if (full) {
            bar.view.setAlpha(0f);
            bar.view.setTranslationY(-dp(10));
        }
        nav.setAlpha(0f);
        nav.setTranslationY(-dp(10));
        info.setAlpha(0f);
        info.setTranslationY(dp(16));
        for (int i = 0; i < appsRow.getChildCount(); i++) {
            View t = appsRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(30));
        }
    }

    private void playEntrance(boolean full) {
        DecelerateInterpolator ease = new DecelerateInterpolator(2.2f);
        if (full) bar.view.animate().alpha(1f).translationY(0f).setDuration(ms(560)).setInterpolator(ease).start();
        nav.animate().alpha(1f).translationY(0f).setStartDelay(ms(80)).setDuration(ms(520)).setInterpolator(ease).start();
        info.animate().alpha(1f).translationY(0f).setStartDelay(ms(160)).setDuration(ms(620)).setInterpolator(ease).start();
        for (int i = 0; i < appsRow.getChildCount(); i++) {
            appsRow.getChildAt(i).animate().alpha(1f).translationY(0f).setStartDelay(ms(220 + i * 55L))
                    .setDuration(ms(560)).setInterpolator(new OvershootInterpolator(0.8f)).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void launchApp(App a) {
        launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Animate the dim only when it changes: a no-op alpha animation still redraws the screen. */
    private void dimTo(float a) {
        if (dimTarget == a) return;
        dimTarget = a;
        dim.animate().alpha(a).setDuration(ms(300)).start();
    }

    private float dimTarget;

    /** The sky only when there's no backdrop: drawn under an opaque image it's pure cost. */
    private void showSky(boolean show) {
        sky.setVisibility(show ? View.VISIBLE : View.GONE);
        sky.setRunning(show && resumed);
    }

    private long ms(long designed) { return Look.ms(this, designed); }

    private int dp(float v) { return Look.dp(this, v); }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    /** Page dots: one per featured title, the current one stretched into a pill. */
    static final class Dots extends View {
        private int n;
        private float shown; // animated position of the pill
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private ValueAnimator anim;

        Dots(Context c) { super(c); }

        void set(int count, int current) {
            n = Math.min(count, 12);
            float to = Math.max(0, Math.min(current, n - 1));
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(shown, to);
            anim.setDuration(Look.ms(getContext(), 380));
            anim.setInterpolator(new DecelerateInterpolator(2f));
            anim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        @Override protected void onDraw(Canvas c) {
            if (n < 2) return;
            float h = getHeight(), d = h * 0.55f, gap = h * 0.6f, pill = d * 3.2f, y = h / 2f;
            float x = 0;
            for (int i = 0; i < n; i++) {
                float k = Math.max(0f, 1f - Math.abs(i - shown)); // 1 at the current dot
                float w = d + (pill - d) * k;
                p.setColor(Color.argb(Math.round(110 + 145 * k), 255, 255, 255));
                r.set(x, y - d / 2, x + w, y + d / 2);
                c.drawRoundRect(r, d / 2, d / 2, p);
                x += w + gap;
            }
        }
    }

    /** 16:9 container. */
    /**
     * A rank in the display serif, as tall as the poster beside it: a faint fill fading downwards
     * and a hairline edge. With focus both warm to the app's colour.
     */
    static final class RankNumber extends View {
        private static final ArgbEvaluator MIX = new ArgbEvaluator();
        private final String n;
        private final int color;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float inset;
        final int wide;
        private float glow;
        private ValueAnimator anim;

        RankNumber(Context c, int rank, int color, int height) {
            super(c);
            n = String.valueOf(rank);
            this.color = color;
            // Size the digits so their ink, not the font's line box, is the poster's height.
            fill.setTypeface(Look.serif(c));
            fill.setTextSize(100);
            Rect ink = new Rect();
            fill.getTextBounds("0", 0, 1, ink);
            fill.setTextSize(100f * height / ink.height());
            fill.setLetterSpacing(-0.06f);
            edge.set(fill);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Look.dp(c, 1.2f));
            fill.getTextBounds(n, 0, n.length(), ink);
            inset = -ink.left + Look.dp(c, 2);
            wide = (int) Math.ceil(ink.width() + Look.dp(c, 4));
        }

        void glow(boolean on, long ms) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(glow, on ? 1f : 0f).setDuration(ms);
            anim.addUpdateListener(v -> { glow = (float) v.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        private int mix(int rest, int lit) { return (int) MIX.evaluate(glow, rest, lit); }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight();
            int hue = color & 0xFFFFFF;
            // Quiet at rest so the posters lead; lit on focus.
            fill.setShader(new LinearGradient(0, 0, 0, h, mix(0x14FFFFFF, 0x8C000000 | hue),
                    mix(0x00FFFFFF, 0x10000000 | hue), Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, 0, 0, h, mix(0x8CFFFFFF, 0xE6FFFFFF),
                    mix(0x14FFFFFF, 0xB3000000 | hue), Shader.TileMode.CLAMP));
            float base = h - edge.getStrokeWidth();
            c.drawText(n, inset, base, fill);
            c.drawText(n, inset, base, edge);
        }
    }

    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by section. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
    }

    /** Horizontal row that scrolls only when focus nears an edge, keeping a margin for the lift. */
    static final class RowScroll extends HorizontalScrollView {
        private final int margin;
        RowScroll(Context c, int margin) {
            super(c);
            this.margin = margin;
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipChildren(false);
            setClipToPadding(false);
        }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            int left = getScrollX() + margin, right = getScrollX() + getWidth() - margin;
            if (r.right > right) return r.right - right;
            if (r.left < left) return r.left - left;
            return 0;
        }
    }
}
