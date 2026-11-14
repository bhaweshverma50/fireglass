package dev.bhawesh.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Remote buttons with another job (idea from cleanyourtv's app_keys): a press and a hold of a
 * button can each open an app, Search, the home, quick settings, mute or go back. HomeService's key
 * filter calls {@link #onKey}; Fire OS 5 allows no such filter, so this is Fire OS 7 and later.
 *
 * Stored in prefs "buttons" as "p:<button>" (press) and "h:<button>" (hold) to an action:
 * "home", "search", "quick", "mute", "back", "none" or "app:<package>". A button is "k<keycode>",
 * or "s<scancode>" for one Android has no keycode for (some app buttons).
 */
final class Buttons {
    private Buttons() { }

    private static final String TAG = "TvHome";
    private static final long HOLD_MS = 500;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("buttons", Context.MODE_PRIVATE); }

    static int count(Context c) { return prefs(c).getAll().size(); }

    static String id(KeyEvent e) { return e.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN ? "k" + e.getKeyCode() : "s" + e.getScanCode(); }

    /** Navigation the remote can't do without: never given another job. */
    static boolean reserved(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_POWER:
                return true;
            default:
                return false;
        }
    }

    static String name(String button) {
        if (button.startsWith("s")) return "Button " + button.substring(1);
        String n = KeyEvent.keyCodeToString(Integer.parseInt(button.substring(1))).replace("KEYCODE_", "").replace('_', ' ').toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    static String label(Context c, String action) {
        switch (action) {
            case "home": return "Fireglass home";
            case "search": return "Search";
            case "quick": return "Quick settings";
            case "mute": return "Mute";
            case "back": return "Back";
            case "none": return "Nothing";
        }
        if (action.startsWith("app:")) {
            PackageManager pm = c.getPackageManager();
            try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(action.substring(4), 0))); }
            catch (PackageManager.NameNotFoundException e) { return action.substring(4); }
        }
        return action;
    }

    // ---------------------------------------------------------------- the key filter

    /** Setup waiting for the button to set up; HomeService hands it the next press. */
    static Setup learning;
    private static String downId;
    private static long downAt;
    private static boolean fired, passedDown;

    /** From HomeService.onKeyEvent: true when the event is used up here. */
    static boolean onKey(AccessibilityService svc, KeyEvent e) {
        if (learning != null) {
            if (e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == KeyEvent.KEYCODE_BACK) return false; // Setup's own Back
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && e.getKeyCode() != KeyEvent.KEYCODE_BACK) {
                Setup s = learning;
                KeyEvent copy = new KeyEvent(e);
                main.post(() -> s.learned(copy));
            }
            return e.getKeyCode() != KeyEvent.KEYCODE_BACK;
        }
        if (reserved(e)) return false;
        String b = id(e);
        SharedPreferences p = prefs(svc);
        String press = p.getString("p:" + b, null), hold = p.getString("h:" + b, null);
        if (press == null && hold == null) return false;

        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                downId = b;
                downAt = e.getEventTime();
                fired = false;
                // Hold only: a short press still belongs to the app, so its down goes through.
                passedDown = press == null;
                if (hold == null) { fired = true; run(svc, press); }
                return !passedDown;
            }
            if (b.equals(downId) && hold != null && !fired && e.getEventTime() - downAt >= HOLD_MS) {
                fired = true;
                run(svc, hold);
            }
            return fired || !passedDown;
        }
        // Up: a press that wasn't held long enough does its press job (or goes on to the app).
        boolean wasFired = fired;
        downId = null;
        if (!wasFired && press != null) { run(svc, press); return true; }
        return wasFired || !passedDown;
    }

    private static void run(AccessibilityService svc, String action) {
        main.post(() -> {
            Log.i(TAG, "remote button: " + action);
            try {
                switch (action) {
                    case "home":
                        svc.startActivity(new Intent(svc, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        break;
                    case "search":
                        svc.startActivity(new Intent(svc, Search.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        break;
                    case "quick": quick(svc); break;
                    case "mute":
                        ((AudioManager) svc.getSystemService(Context.AUDIO_SERVICE))
                                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                        break;
                    case "back": svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); break;
                    case "none": break;
                    default:
                        if (!action.startsWith("app:")) break;
                        PackageManager pm = svc.getPackageManager();
                        String pkg = action.substring(4);
                        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                        if (i != null) svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "remote button " + action, e);
            }
        });
    }

    // ---------------------------------------------------------------- quick settings

    private static View panel;

    /** A small panel over whatever is on screen (cleanyourtv's settings_key_panel): settings without leaving it. */
    static void quick(AccessibilityService svc) {
        WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        if (panel != null) { close(wm); return; }
        FrameLayout root = new FrameLayout(svc) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                    if (e.getAction() == KeyEvent.ACTION_UP) close(wm);
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        View shade = new View(svc);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xCC000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout list = new LinearLayout(svc);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Look.dp(svc, 10);
        list.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Look.dp(svc, 24));
        bg.setColor(0xF017171E);
        bg.setStroke(Look.dp(svc, 1), 0x26FFFFFF);
        list.setBackground(bg);
        TextView title = Look.text(svc, Look.sans(svc, 600), 13, 0x8CFFFFFF);
        title.setText("Quick settings");
        title.setPadding(Look.dp(svc, 16), Look.dp(svc, 6), 0, Look.dp(svc, 8));
        list.addView(title);
        Object[][] rows = {
                {"Customise home", new Intent(svc, HomeSettings.class)},
                {"Network", new Intent(Settings.ACTION_WIFI_SETTINGS)},
                {"Applications", new Intent(Settings.ACTION_APPLICATION_SETTINGS)},
                {"Remotes & Bluetooth", new Intent("android.settings.CONTROLLERS_SETTINGS")},
                {"All settings", null},
        };
        View first = null;
        for (Object[] r : rows) {
            TextView row = sheetRow(svc, (String) r[0]);
            Intent target = (Intent) r[1];
            row.setOnClickListener(v -> {
                close(wm);
                if (target == null) { HomeService.openAmazonSettings(svc); return; }
                try {
                    svc.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                } catch (RuntimeException e) {
                    Log.w(TAG, "quick settings " + r[0], e);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, Look.dp(svc, 46)));
            if (first == null) first = row;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Look.dp(svc, 300), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.setMargins(0, 0, Look.dp(svc, Look.PAD_X), 0);
        root.addView(list, lp);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(root, wlp);
        } catch (RuntimeException e) {
            Log.w(TAG, "quick settings panel", e);
            return;
        }
        panel = root;
        list.setAlpha(0f);
        list.setTranslationX(Look.dp(svc, 24));
        list.animate().alpha(1f).translationX(0f).setDuration(Look.ms(svc, 220)).start();
        View f = first;
        root.post(f::requestFocus);
    }

    private static void close(WindowManager wm) {
        View v = panel;
        panel = null;
        if (v != null) wm.removeViewImmediate(v);
    }

    /** A row as in the home's option sheets: white highlight with dark text when focused. */
    static TextView sheetRow(Context c, String text) {
        TextView t = Look.text(c, Look.sans(c, 500), 15, Color.WHITE);
        t.setText(text);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Look.dp(c, 16), 0, Look.dp(c, 16), 0);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable hl = new GradientDrawable();
        hl.setCornerRadius(Look.dp(c, 14));
        hl.setColor(0x00FFFFFF);
        t.setBackground(hl);
        t.setOnFocusChangeListener((v, has) -> {
            hl.setColor(has ? 0xF2FFFFFF : 0x00FFFFFF);
            t.setTextColor(has ? 0xFF16161C : Color.WHITE);
        });
        return t;
    }

    // ---------------------------------------------------------------- setup screen

    /** Customise home > Remote buttons: what's set, and adding one (press it, then pick the jobs). */
    public static final class Setup extends Activity {
        private LinearLayout list;
        private TextView title, hint;
        private String button;  // being set up
        private boolean hold;   // which of its jobs

        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Color.BLACK);
            SkyView sky = new SkyView(this);
            sky.setLook(HomeSettings.get(this, HomeSettings.LOOK));
            sky.setAccent(MainActivity.accent, 0.4f, false);
            root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
            View dim = new View(this);
            dim.setBackgroundColor(0xB3000000);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView kicker = Look.text(this, Look.sans(this, 600), 13, 0x8CFFFFFF);
            kicker.setText("Customise home");
            left.addView(kicker);
            title = Look.text(this, Look.serif(this), 40, Color.WHITE);
            left.addView(title);
            hint = Look.text(this, Look.sans(this, 400), 15, 0xA6FFFFFF);
            hint.setLineSpacing(Look.dp(this, 4), 1f);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.setMargins(0, Look.dp(this, 14), 0, 0);
            left.addView(hint, hp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Look.dp(this, 380), -2, Gravity.START | Gravity.CENTER_VERTICAL);
            lp.setMargins(Look.dp(this, Look.PAD_X), 0, 0, 0);
            root.addView(left, lp);

            list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            int pad = Look.dp(this, 10);
            list.setPadding(pad, pad, pad, pad);
            list.setBackground(new Look.Glass(Look.dp(this, 24), 0.8f));
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(list);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(Look.dp(this, 420), -2, Gravity.END | Gravity.CENTER_VERTICAL);
            rp.setMargins(0, Look.dp(this, 28), Look.dp(this, Look.PAD_X), Look.dp(this, 28));
            root.addView(sv, rp);
            setContentView(root);
            showMain();
        }

        @Override protected void onPause() { super.onPause(); if (learning == this) learning = null; }

        @Override public void onBackPressed() {
            if (button != null || learning == this) { learning = null; button = null; showMain(); return; }
            finish();
        }

        private void rows(List<String> labels, List<Runnable> actions) {
            list.removeAllViews();
            View first = null;
            for (int i = 0; i < labels.size(); i++) {
                TextView r = sheetRow(this, labels.get(i));
                Runnable a = actions.get(i);
                r.setOnClickListener(v -> a.run());
                list.addView(r, new LinearLayout.LayoutParams(-1, Look.dp(this, 46)));
                if (first == null) first = r;
            }
            if (first != null) first.requestFocus();
        }

        private void showMain() {
            title.setText("Remote buttons");
            hint.setText("Give a button on your remote another job, for a press, a hold, or both. "
                    + "OK on one that's set takes it off. The arrows, OK, Back and Home stay as they are.");
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            Map<String, ?> all = new TreeMap<>(prefs(this).getAll());
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                labels.add(name(k.substring(2)) + (k.startsWith("h:") ? " (hold)" : "") + "  →  " + label(this, String.valueOf(e.getValue())));
                actions.add(() -> { prefs(this).edit().remove(k).apply(); showMain(); });
            }
            labels.add("Add a button");
            actions.add(this::listen);
            rows(labels, actions);
        }

        private void listen() {
            title.setText("Press a button");
            hint.setText("Press the button on your remote that should do something else. Back cancels.");
            list.removeAllViews();
            TextView wait = Look.text(this, Look.sans(this, 500), 15, 0xCCFFFFFF);
            wait.setText("Waiting for a button…");
            wait.setPadding(Look.dp(this, 16), Look.dp(this, 14), Look.dp(this, 16), Look.dp(this, 14));
            list.addView(wait);
            learning = this;
        }

        /** From the key filter (or this screen, if the filter isn't running). */
        void learned(KeyEvent e) {
            if (learning != this) return;
            learning = null;
            if (reserved(e)) {
                listen();
                hint.setText(name(id(e)) + " can't be changed. Press another button, or Back to cancel.");
                return;
            }
            button = id(e);
            title.setText(name(button));
            hint.setText("A press, or holding it for half a second?");
            rows(java.util.Arrays.asList("When pressed", "When held"),
                    java.util.Arrays.asList(() -> pick(false), () -> pick(true)));
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            // HomeService normally hands the button over; without it, catch it here.
            if (learning == this && e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() != KeyEvent.KEYCODE_BACK) {
                learned(e);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        private void pick(boolean held) {
            hold = held;
            hint.setText(name(button) + (held ? ", held" : ", pressed") + ": what should it do?");
            List<String> labels = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (String a : new String[]{"home", "search", "quick", "mute", "back", "none"}) { labels.add(label(this, a)); values.add(a); }
            PackageManager pm = getPackageManager();
            List<String> seen = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
                seen.add(pkg);
                labels.add("Open " + ri.loadLabel(pm));
                values.add("app:" + pkg);
            }
            List<Runnable> actions = new ArrayList<>();
            for (String v : values) actions.add(() -> {
                prefs(this).edit().putString((hold ? "h:" : "p:") + button, v).apply();
                button = null;
                showMain();
            });
            rows(labels, actions);
        }
    }
}
