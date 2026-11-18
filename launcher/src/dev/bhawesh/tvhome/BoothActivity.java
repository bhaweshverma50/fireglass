package dev.bhawesh.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Booth on the TV: the home lab behind the TV, from the couch. The media pipeline as a row of
 * posters by stage, the server phone's vitals, services with lamps and Restart / Start / Stop,
 * requests to approve, and active alerts. The first time (or after sign-out) it asks for the
 * address and password; only the session cookie is kept (see Booth).
 *
 * Perf: one poll every 20 s while in front; posters decoded small, held in a handful of bitmaps and
 * released in onStop; all network on a worker thread; no blur, no full-screen blended panel beyond
 * the same sky and dim the other screens use.
 */
public class BoothActivity extends Activity {
    private static final long POLL_MS = 20_000;
    private static final int POSTER_W = 80, POSTER_H = 120;
    private static final int VITAL_H = 62, SVC_H = 34;
    private static final int LANE_GAP = 30, VITALS_W = 300, COL_GAP = 28, CARD_GAP = 10;
    private static final boolean OWN_KEYS = android.os.Build.VERSION.SDK_INT < 23;
    private static final int GREEN = 0xFF5BD68A, AMBER = 0xFFFFC24D, RED = 0xFFFF6B6B, GREY = 0xFF8A8A96;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Runnable poll = this::refresh;

    private FrameLayout root;
    private SkyView sky;
    private View screen;           // sign-in or dashboard
    private FrameLayout sheet;     // the action sheet over the dashboard, or null
    private View beforeSheet;
    private boolean resumed;
    private int gen;               // bumped when pictures are released: late loads then drop theirs

    // dashboard
    private ScrollView scroll;
    private LinearLayout pipelineRow, vitals, services, requests, alerts;
    private TextView status, kicker;
    private JSONObject data;
    private boolean loading, dashboard;
    private long lastOk;
    private final Map<String, String> sigs = new HashMap<>();
    // posters: views waiting for their picture, and the pictures kept
    private final List<Object[]> tiles = new ArrayList<>();      // {ImageView, path, widthPx}
    private final Map<String, Bitmap> mem = new HashMap<>();

    // sign-in
    private TextView note;
    private EditText hostField, passField;
    private EditText active;
    private boolean shift, reveal;
    private int wrong;
    private long lockedUntil;
    private LinearLayout keyBox;
    private TextView shiftKey, showKey;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sky = new SkyView(this);
        sky.setLook(HomeSettings.get(this, HomeSettings.LOOK));
        sky.setAccent(MainActivity.accent, 0.4f, false);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        boolean photo = HomeSettings.get(this, HomeSettings.LOOK) == SkyView.WALLPAPER;
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, photo
                ? new int[]{0xCC000000, 0xE0000000, 0xE6000000} : new int[]{0x99000000, 0xB3000000, 0xCC000000}));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        photoSmoke = photo ? 0.8f : 0f;
        if (Booth.signedIn(this)) showDashboard(); else showSignIn(null);
    }

    private float photoSmoke;

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        sky.setRunning(true);
        if (data != null) { loadPictures(); refresh(); }
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        sky.setRunning(false);
        handler.removeCallbacks(poll);
    }

    /** Pictures are the heavy part: let them go while away; onResume loads them again. */
    @Override protected void onStop() {
        super.onStop();
        gen++;
        for (Object[] t : tiles) ((ImageView) t[0]).setImageDrawable(null);
        for (Bitmap bm : mem.values()) bm.recycle();
        mem.clear();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (OWN_KEYS && keyBox != null && keyBox.isAttachedToWindow() && active != null
                && e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DEL) {
                int n = active.length();
                if (n > 0) active.getText().delete(n - 1, n);
                return true;
            }
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) { submit(); return true; }
            int ch = e.getUnicodeChar();
            if (ch >= 0x20 && ch != 0x7F && !e.isCtrlPressed()) { active.append(String.valueOf((char) ch)); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {
        if (sheet != null) { closeSheet(); return; }
        super.onBackPressed();
    }

    // ================================================================ sign-in

    private void show(View v) {
        if (screen != null) root.removeView(screen);
        screen = v;
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0f);
        v.setTranslationY(dp(12));
        v.animate().alpha(1f).translationY(0f).setDuration(Look.ms(this, 420)).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void showSignIn(String message) {
        handler.removeCallbacks(poll);
        dashboard = false;
        loading = false;
        data = null;
        tiles.clear();
        sigs.clear();
        sheet = null;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(Look.PAD_X), dp(Look.PAD_TOP), dp(Look.PAD_X), 0);
        TextView k = Look.text(this, Look.sans(this, 600), 13, 0x8CFFFFFF);
        k.setText("Home lab");
        col.addView(k);
        TextView title = Look.text(this, Look.serif(this), 40, Color.WHITE);
        title.setText("Booth");
        col.addView(title, lp(-2, -2, dp(4), 0));
        note = Look.text(this, Look.sans(this, 500), 14, 0xB3FFFFFF);
        note.setText(message != null ? message : "Sign in to the server behind the TV. Only a session is kept here, never the password.");
        col.addView(note, lp(-2, -2, dp(8), dp(16)));

        hostField = field("Server address", false);
        hostField.setText(Booth.host(this));
        passField = field("Password", true);
        LinearLayout fields = new LinearLayout(this);
        fields.addView(wrapField(hostField, dp(330)), lp(dp(330), dp(52), 0, 0, dp(16)));
        fields.addView(wrapField(passField, dp(300)), lp(dp(300), dp(52), 0, 0));
        col.addView(fields);
        if (OWN_KEYS) {
            active = passField;
            markActive();
            col.addView(buildKeys(), lp(-2, -2, dp(14), 0));
        } else {
            active = passField;
            markActive();
            TextView go = pill("Sign in", true);
            go.setOnClickListener(v -> submit());
            col.addView(go, lp(dp(130), dp(40), dp(18), 0));
            passField.setOnEditorActionListener((v, a, e) -> { submit(); return true; });
        }
        show(col);
        // Wrong or unknown: start where the next thing to type is.
        if (OWN_KEYS) ((LinearLayout) keyBox.getChildAt(0)).getChildAt(0).requestFocus();
        else passField.requestFocus();
    }

    private EditText field(String hint, boolean password) {
        EditText f = new EditText(this);
        f.setTypeface(Look.sans(this, 500));
        f.setTextSize(18);
        f.setTextColor(Color.WHITE);
        f.setHintTextColor(0x66FFFFFF);
        f.setHint(hint);
        f.setBackground(null);
        f.setSingleLine(true);
        f.setPadding(dp(8), 0, dp(8), 0);
        f.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | (password ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT));
        f.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        f.setTypeface(Look.sans(this, 500)); // after setInputType: password mode switches to monospace
        if (OWN_KEYS) {
            f.setFocusable(false); // shows what the keys below type
            f.setShowSoftInputOnFocus(false);
        } else {
            f.setOnFocusChangeListener((v, has) -> {
                if (!has) return;
                active = f;
                markActive();
                handler.postDelayed(() -> showKeyboard(f), 250);
            });
        }
        return f;
    }

    private View wrapField(EditText f, int w) {
        FrameLayout pill = new FrameLayout(this);
        pill.setBackground(new Look.Glass(dp(26), 0.95f));
        pill.addView(f, new FrameLayout.LayoutParams(-1, -1));
        pill.setPadding(dp(14), 0, dp(14), 0);
        f.setTag(pill);
        return pill;
    }

    /** The field the keys type into is the bright one. */
    private void markActive() {
        for (EditText f : new EditText[]{hostField, passField}) {
            View pill = (View) f.getTag();
            if (pill == null) continue;
            pill.animate().alpha(f == active ? 1f : 0.55f).setDuration(Look.ms(this, 160)).start();
        }
    }

    private void showKeyboard(EditText f) {
        if (f.hasFocus()) ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(f, 0);
    }

    private static final String[] ROWS = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String SYMBOLS = ".:-_/@!?#$%&*+=";

    /** Our own keys on Fire OS 5 (its keyboard is a dialog covering the screen): the remote types into the active field. */
    private LinearLayout buildKeys() {
        LinearLayout box = keyBox = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        for (String row : ROWS) {
            LinearLayout r = new LinearLayout(this);
            r.setClipChildren(false);
            for (char ch : row.toCharArray()) {
                TextView[] self = new TextView[1];
                self[0] = key(String.valueOf(ch), 40, () -> type(self[0].getText().toString()));
                r.addView(self[0]);
            }
            box.addView(r, lp(-2, -2, box.getChildCount() == 0 ? 0 : dp(6), 0));
        }
        LinearLayout sym = new LinearLayout(this);
        sym.setClipChildren(false);
        for (char ch : SYMBOLS.toCharArray()) sym.addView(key(String.valueOf(ch), 40, () -> type(String.valueOf(ch))));
        box.addView(sym, lp(-2, -2, dp(6), 0));
        LinearLayout fn = new LinearLayout(this);
        fn.setClipChildren(false);
        shiftKey = key("Shift", 62, this::toggleShift);
        fn.addView(shiftKey);
        fn.addView(key("Delete", 62, () -> { int n = active.length(); if (n > 0) active.getText().delete(n - 1, n); }));
        fn.addView(key("Clear", 56, () -> active.setText("")));
        showKey = key("Show", 56, () -> {
            reveal = !reveal;
            passField.setInputType(reveal ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passField.setTypeface(Look.sans(this, 500));
            showKey.setText(reveal ? "Hide" : "Show");
        });
        fn.addView(showKey);
        fn.addView(key("Address", 66, () -> switchField(hostField)));
        fn.addView(key("Password", 70, () -> switchField(passField)));
        TextView go = key("Sign in", 76, this::submit);
        go.setTag("primary");
        fn.addView(go);
        box.addView(fn, lp(-2, -2, dp(6), 0));
        return box;
    }

    private void switchField(EditText f) {
        active = f;
        markActive();
    }

    private void toggleShift() {
        shift = !shift;
        shiftKey.setText(shift ? "SHIFT" : "Shift");
        for (int i = 0; i < keyBox.getChildCount(); i++) {
            LinearLayout r = (LinearLayout) keyBox.getChildAt(i);
            for (int j = 0; j < r.getChildCount(); j++) {
                TextView t = (TextView) r.getChildAt(j);
                String s = t.getText().toString();
                if (s.length() == 1 && Character.isLetter(s.charAt(0))) t.setText(shift ? s.toUpperCase() : s.toLowerCase());
            }
        }
    }

    private void type(String s) {
        if (active.length() < 80) active.append(s);
        if (shift) toggleShift(); // one capital at a time
    }

    private TextView key(String label, int w, Runnable act) {
        TextView k = Look.text(this, Look.sans(this, 600), label.length() == 1 ? 17 : 12, 0xE6FFFFFF);
        k.setText(label);
        k.setGravity(Gravity.CENTER);
        k.setFocusable(true);
        k.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x1AFFFFFF);
        k.setBackground(bg);
        k.setOnClickListener(v -> act.run());
        k.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x1AFFFFFF);
            k.setTextColor(has ? 0xFF111114 : 0xE6FFFFFF);
            k.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(has ? 160 : 120).start();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(w), dp(36));
        p.setMargins(0, 0, dp(6), 0);
        k.setLayoutParams(p);
        return k;
    }

    private void submit() {
        long now = SystemClock.elapsedRealtime();
        if (now < lockedUntil) {
            note.setText("Paused to protect the server's five-try limit. Try again in " + ((lockedUntil - now) / 60_000 + 1) + " min.");
            return;
        }
        String host = Booth.cleanHost(hostField.getText().toString());
        String pass = passField.getText().toString();
        if (host == null) { note.setText("That doesn't look like an address. Try 192.168.0.12:8888."); return; }
        if (pass.isEmpty()) { note.setText("Type the password first."); return; }
        note.setText("Signing in to " + host + "…");
        io.execute(() -> {
            String err = null;
            int code = 0;
            try {
                Booth.login(this, host, pass);
            } catch (Booth.Failure f) {
                err = f.getMessage();
                code = f.code;
            } catch (IOException e) {
                err = "Can't reach Booth at " + host + ".";
            }
            String e = err;
            int c = code;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (e == null) { wrong = 0; showDashboard(); return; }
                if (c == 401 && ++wrong >= 3) {
                    // The server locks an address out after five wrong passwords: stop well short of it.
                    lockedUntil = SystemClock.elapsedRealtime() + 10 * 60_000;
                    note.setText("Wrong password three times. Paused for 10 minutes so the server doesn't lock this TV out.");
                } else if (c == 0 && e.startsWith("Can't reach")) {
                    note.setText(e + " Is it on, and is the address right?");
                } else {
                    note.setText(e);
                }
                if (c == 429) lockedUntil = SystemClock.elapsedRealtime() + 10 * 60_000;
            });
        });
    }

    // ================================================================ dashboard

    private void showDashboard() {
        dashboard = true;
        sigs.clear();
        tiles.clear();
        FrameLayout wrap = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(24));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        // One left edge for everything: the launcher's own margin.
        col.setPadding(dp(Look.PAD_X), dp(Look.PAD_TOP), dp(Look.PAD_X), 0);
        scroll.addView(col);
        wrap.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // Header: title and where it runs on the left; freshness and actions on the right, centred on the title.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = Look.text(this, Look.serif(this), 36, Color.WHITE);
        title.setText("Booth");
        names.addView(title);
        kicker = Look.text(this, Look.sans(this, 500), 13, 0x99FFFFFF);
        kicker.setText(Booth.host(this));
        names.addView(kicker, lp(-2, -2, dp(2), 0));
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        status = Look.text(this, Look.sans(this, 500), 13, 0x99FFFFFF);
        head.addView(status, lp(-2, -2, 0, 0, dp(16)));
        TextView again = pill("Refresh", false);
        again.setTag("refresh");
        again.setOnClickListener(v -> refresh());
        head.addView(again, lp(dp(88), dp(36), 0, 0, dp(10)));
        TextView out = pill("Sign out", false);
        out.setTag("signout");
        out.setOnClickListener(v -> signOut());
        head.addView(out, lp(dp(88), dp(36), 0, 0));
        col.addView(head, lp(-1, -2, 0, 0));

        // Alerts only take space when there is something to say.
        alerts = new LinearLayout(this);
        alerts.setOrientation(LinearLayout.VERTICAL);
        col.addView(alerts, lp(-1, -2, dp(14), 0));

        // The pipeline: four lanes, each a title over cards of one size.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(6), 0, dp(6)); // room for a lifted poster
        pipelineRow = new LinearLayout(this);
        pipelineRow.setClipChildren(false);
        hs.addView(pipelineRow);
        col.addView(hs, lp(-1, -2, dp(12), 0));

        // Bottom: the phone on the left, its services on the right; both start on the same line.
        LinearLayout cols = new LinearLayout(this);
        cols.setClipChildren(false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(heading("Server phone", 0));
        vitals = new LinearLayout(this);
        vitals.setOrientation(LinearLayout.VERTICAL);
        left.addView(vitals, lp(-1, -2, dp(8), 0));
        cols.addView(left, new LinearLayout.LayoutParams(dp(VITALS_W), -2));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(heading("Services", 0));
        services = new LinearLayout(this);
        services.setOrientation(LinearLayout.VERTICAL);
        right.addView(services, lp(-1, -2, dp(8), 0));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.setMargins(dp(COL_GAP), 0, 0, 0);
        cols.addView(right, rp);
        col.addView(cols, lp(-1, -2, dp(10), 0));
        requests = null; // requests are handled from the Waiting lane

        show(wrap);
        status.setText("Loading…");
        loading = false;
        refresh();
        // Something to land on while the first answer is coming.
        again.requestFocus();
    }

    private TextView heading(String text, int top) {
        TextView h = Look.text(this, Look.sans(this, 600), 13, 0x99FFFFFF);
        h.setText(text);
        h.setLayoutParams(lp(-2, -2, top, 0));
        return h;
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        Look.Glass g = new Look.Glass(dp(24), 0.8f);
        g.setSmoke(photoSmoke);
        p.setBackground(g);
        p.setPadding(dp(8), dp(8), dp(8), dp(8));
        return p;
    }

    private TextView pill(String label, boolean primary) {
        TextView t = Look.text(this, Look.sans(this, 600), 13, primary ? 0xFF16161C : 0xF2FFFFFF);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(primary ? 0xF2FFFFFF : 0x24FFFFFF);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : primary ? 0xE6FFFFFF : 0x24FFFFFF);
            t.setTextColor(has || primary ? 0xFF16161C : 0xF2FFFFFF);
            t.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(Look.ms(this, has ? 160 : 120)).start();
        });
        return t;
    }

    private void signOut() {
        io.execute(() -> {
            try { Booth.post(this, "/api/logout"); } catch (IOException ignored) { }
            Booth.signOut(this);
            runOnUiThread(() -> { if (!isDestroyed()) showSignIn("Signed out. Sign in again, or point Booth at another server."); });
        });
    }

    // ---------------------------------------------------------------- polling

    private void refresh() {
        handler.removeCallbacks(poll);
        if (loading || !dashboard) return;
        loading = true;
        io.execute(() -> {
            JSONObject d = null;
            String err = null;
            int code = 0;
            try {
                d = Booth.getObject(this, "/api/overview");
            } catch (Booth.Failure f) {
                err = f.getMessage();
                code = f.code;
            } catch (IOException e) {
                err = "Can't reach Booth.";
            }
            JSONObject got = d;
            String e = err;
            int c = code;
            runOnUiThread(() -> {
                loading = false;
                if (isDestroyed() || !dashboard) return;
                if (c == 401) { showSignIn("Signed out. Sign in again."); return; }
                if (got != null) {
                    data = got;
                    lastOk = SystemClock.elapsedRealtime();
                    render(got);
                    if (busyNote == null) status.setText("Updated " + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date()));
                } else if (busyNote == null) {
                    status.setText(e + (data != null ? " Showing the last answer." : ""));
                }
                if (resumed) handler.postDelayed(poll, POLL_MS);
            });
        });
    }

    // ---------------------------------------------------------------- rendering

    /** Rebuild only the parts that changed, and keep the focused control focused. */
    private void render(JSONObject d) {
        View had = getCurrentFocus();
        Object focusTag = had == null ? null : had.getTag();
        renderVitals(d.optJSONObject("metrics"));
        boolean changed = false;
        JSONArray recent = d.optJSONArray("recent");
        changed |= changed("pipeline", sig(d.optJSONArray("requests"), "id", "title") + sig(d.optJSONArray("wanted"), "id", "title")
                + sigQueue(d.optJSONArray("queue")) + sig(recent, "title", "year"));
        if (changed) { renderPipeline(d); }
        if (changed("services", sigServices(d.optJSONArray("services")))) { renderServices(d.optJSONArray("services")); changed = true; }
        if (changed("requests", sig(d.optJSONArray("requests"), "id", "title"))) { renderRequests(d.optJSONArray("requests")); changed = true; }
        if (changed("alerts", sig(d.optJSONArray("alerts"), "key", "title"))) { renderAlerts(d.optJSONArray("alerts")); changed = true; }
        if (changed) {
            loadPictures();
            if (focusTag != null && sheet == null) {
                View v = findTagged(screen, focusTag);
                if (v != null) v.requestFocus();
            }
        }
    }

    private boolean changed(String key, String sig) {
        if (sig.equals(sigs.get(key))) return false;
        sigs.put(key, sig);
        return true;
    }

    private static String sig(JSONArray a, String k1, String k2) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) b.append(o.opt(k1)).append(':').append(o.opt(k2)).append(';');
        }
        return b.append('|').toString();
    }

    private static String sigQueue(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) b.append(o.optString("title")).append(o.optInt("progress")).append(o.optString("state")).append(';');
        }
        return b.append('|').toString();
    }

    private static String sigServices(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) b.append(o.optString("name")).append(o.optBoolean("running")).append(o.optBoolean("disabled"))
                    .append(o.optInt("mem_mb") / 50).append(o.optInt("uptime_s") / 3600).append(';');
        }
        return b.toString();
    }

    private static View findTagged(View v, Object tag) {
        if (tag.equals(v.getTag()) && v.isFocusable()) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findTagged(g.getChildAt(i), tag);
                if (f != null) return f;
            }
        }
        return null;
    }

    // ---- pipeline

    private void renderPipeline(JSONObject d) {
        dropTiles(pipelineRow);
        pipelineRow.removeAllViews();
        JSONArray waiting = d.optJSONArray("requests"), queue = d.optJSONArray("queue"),
                wanted = d.optJSONArray("wanted"), recent = d.optJSONArray("recent");
        // Searching: monitored with no file yet, and not already downloading.
        List<JSONObject> searching = new ArrayList<>();
        for (int i = 0; wanted != null && i < wanted.length(); i++) {
            JSONObject w = wanted.optJSONObject(i);
            if (w != null && !downloading(queue, w.optString("title"))) searching.add(w);
        }
        stage("To approve", count(waiting), arr(waiting), "waiting");
        stage("Searching", searching.size(), searching, "searching");
        stage("Downloading", count(queue), arr(queue), "downloading");
        stage("Ready", count(recent), arr(recent), "ready");
    }

    private static boolean downloading(JSONArray queue, String title) {
        for (int i = 0; queue != null && i < queue.length(); i++) {
            JSONObject q = queue.optJSONObject(i);
            if (q != null && q.optString("title").startsWith(title)) return true;
        }
        return false;
    }

    private static int count(JSONArray a) { return a == null ? 0 : a.length(); }

    private static List<JSONObject> arr(JSONArray a) {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) l.add(o); }
        return l;
    }

    private void stage(String name, int n, List<JSONObject> items, String kind) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setClipChildren(false);
        // Lane title: count first (it is what changes), then the name. Sits exactly over the first card.
        LinearLayout label = new LinearLayout(this);
        label.setGravity(Gravity.BOTTOM);
        TextView num = Look.text(this, Look.sans(this, 700), 17, n > 0 ? Color.WHITE : 0x59FFFFFF);
        num.setText(String.valueOf(n));
        label.addView(num);
        TextView t = Look.text(this, Look.sans(this, 600), 13, n > 0 ? 0xE6FFFFFF : 0x80FFFFFF);
        t.setText(name);
        label.addView(t, lp(-2, -2, 0, dp(3), 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).setMargins(dp(7), 0, 0, dp(1));
        g.addView(label, lp(-2, -2, 0, dp(8)));

        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        int shown = Math.min(items.size(), 12);
        for (int i = 0; i < shown; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(POSTER_W), -2);
            p.setMargins(i == 0 ? 0 : dp(CARD_GAP + 4), 0, 0, 0);
            row.addView(tile(items.get(i), kind), p);
        }
        if (shown == 0) {
            // Same footprint as a poster, so every lane lines up whether it is empty or not.
            TextView none = Look.text(this, Look.sans(this, 500), 12, 0x8CFFFFFF);
            none.setText(kind.equals("waiting") ? "No requests" : kind.equals("searching") ? "Nothing missing"
                    : kind.equals("downloading") ? "No downloads" : "Nothing new");
            none.setGravity(Gravity.CENTER);
            none.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(Look.TILE_RADIUS));
            bg.setColor(0x14FFFFFF);
            bg.setStroke(dp(1), 0x1AFFFFFF);
            none.setBackground(bg);
            row.addView(none, new LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)));
        }
        g.addView(row);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.setMargins(pipelineRow.getChildCount() == 0 ? 0 : dp(LANE_GAP), 0, 0, 0);
        pipelineRow.addView(g, gp);
    }

    /** One title: poster (progress on it while downloading), name and a dim line. */
    private View tile(JSONObject it, String kind) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(kind + ":" + it.optString("id", it.optString("hash", it.optString("title"))) + it.optString("title"));

        FrameLayout pic = new FrameLayout(this);
        pic.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A2A33, 0xFF121217}));
        Look.roundClip(pic, dp(Look.TILE_RADIUS));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        String title = it.optString("title");
        TextView initial = Look.text(this, Look.serif(this), 30, 0x66FFFFFF);
        initial.setText(title.isEmpty() ? "" : title.substring(0, 1).toUpperCase());
        initial.setGravity(Gravity.CENTER);
        pic.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        img.setTag(initial); // hidden once the picture is in
        String poster = it.isNull("poster") ? null : it.optString("poster", null);
        tiles.add(new Object[]{img, poster, dp(POSTER_W) * 2});

        String sub;
        if (kind.equals("downloading")) {
            int pct = it.optInt("progress");
            View bar = new Meter(this, pct / 100f, 0xFF6AB7FF, 0xA6000000);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, dp(5), Gravity.BOTTOM);
            pic.addView(bar, bp);
            boolean paused = it.optString("state").toLowerCase().contains("paused") || it.optString("state").toLowerCase().contains("stopped");
            sub = paused ? "Paused · " + pct + "%" : pct + "%" + (it.has("speed_mbps") ? " · " + it.optDouble("speed_mbps") + " Mb/s" : "")
                    + (!it.isNull("eta") && !it.optString("eta").isEmpty() ? " · " + it.optString("eta") : "");
            if (!it.optString("problem").isEmpty()) sub = "Problem: " + it.optString("problem");
        } else if (kind.equals("waiting")) {
            sub = (it.optString("by").isEmpty() || it.isNull("by") ? "Requested" : "From " + it.optString("by"));
        } else if (kind.equals("searching")) {
            sub = it.has("missing") ? it.optInt("missing") + " episodes missing" : it.optString("year", "");
        } else {
            sub = it.optString("kind", "") + (it.isNull("year") ? "" : " · " + it.optString("year"));
        }
        View rim = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(dp(Look.TILE_RADIUS));
        rg.setStroke(dp(2), 0xE6FFFFFF);
        rim.setBackground(rg);
        rim.setAlpha(0f);
        pic.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        card.addView(pic, new LinearLayout.LayoutParams(-1, dp(POSTER_H)));

        TextView name = Look.text(this, Look.sans(this, 600), 12, Color.WHITE);
        name.setText(title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name, lp(-1, -2, dp(6), 0));
        TextView line = Look.text(this, Look.sans(this, 500), 11, 0x8CFFFFFF);
        line.setText(sub);
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(line, lp(-1, -2, dp(2), 0));

        card.setOnFocusChangeListener((v, has) -> {
            pic.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
                    .setInterpolator(has ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator(2f))
                    .setDuration(Look.ms(this, has ? 380 : 220)).start();
            rim.animate().alpha(has ? 1f : 0f).setDuration(180).start();
        });
        card.setOnClickListener(v -> tileActions(it, kind));
        return card;
    }

    private void tileActions(JSONObject it, String kind) {
        String title = it.optString("title");
        if (kind.equals("waiting")) {
            Sheet s = new Sheet(title, (it.optString("kind").equals("tv") ? "Series" : "Film") + " requested"
                    + (it.isNull("by") || it.optString("by").isEmpty() ? "" : " by " + it.optString("by")) + ". Waiting for your approval.");
            s.add("Approve", () -> act("Approving " + title + "…", "/api/requests/" + it.optInt("id") + "/approve"));
            s.add("Decline", () -> act("Declining " + title + "…", "/api/requests/" + it.optInt("id") + "/decline"));
            s.add("Close", null);
            s.show(0);
        } else if (kind.equals("searching")) {
            Sheet s = new Sheet(title, "Monitored, and no copy yet. Search again looks for a release now.");
            s.add("Search again", () -> act("Searching for " + title + "…", "/api/search/" + it.optString("app") + "/" + it.optInt("id")));
            s.add("Close", null);
            s.show(0);
        } else if (kind.equals("downloading")) {
            String hash = it.optString("hash");
            Sheet s = new Sheet(title, "Downloading, " + it.optInt("progress") + "% of " + it.optDouble("size_gb") + " GB"
                    + (it.isNull("quality") ? "" : " · " + it.optString("quality")) + ".");
            boolean paused = it.optString("state").toLowerCase().contains("paused") || it.optString("state").toLowerCase().contains("stopped");
            if (hash.matches("[0-9a-f]{40}")) {
                s.add(paused ? "Resume" : "Pause", () -> act((paused ? "Resuming " : "Pausing ") + title + "…", "/api/torrent/" + hash + "/" + (paused ? "resume" : "pause")));
            }
            s.add("Close", null);
            s.show(0);
        }
    }

    // ---- phone vitals

    private void renderVitals(JSONObject m) {
        vitals.removeAllViews();
        if (m == null) return;
        double temp = m.optDouble("cpu_temp", Double.NaN), bt = m.optDouble("battery_temp", 0);
        double free = m.optDouble("disk_free_gb", 0), total = m.optDouble("disk_total_gb", 1);
        int bat = m.optInt("battery"), cpu = m.isNull("cpu") ? -1 : m.optInt("cpu");
        String state = m.optString("charging");
        String power = state.equalsIgnoreCase("Charging") ? "Charging" : state.equalsIgnoreCase("Full") ? "Full" : "Not charging";
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(vital("Battery", bat + "%", power, bat / 100f, bat < 20 ? RED : bat < 40 ? AMBER : GREEN), vp(0));
        r1.addView(vital("Heat", Math.round(bt) + "°C", Double.isNaN(temp) ? "Battery" : "Chip " + Math.round(temp) + "°C",
                (float) Math.min(1, bt / 50), bt >= 42 ? RED : bt >= 38 ? AMBER : GREEN), vp(dp(CARD_GAP)));
        r2.addView(vital("Storage", Math.round(free) + " GB", "free of " + Math.round(total) + " GB", (float) ((total - free) / total),
                free < 10 ? RED : free < 25 ? AMBER : GREEN), vp(0));
        r2.addView(vital("Processor", cpu < 0 ? "…" : cpu + "%", fmt1(m.optDouble("mem_used_gb")) + " of " + fmt1(m.optDouble("mem_total_gb")) + " GB memory",
                Math.max(0, cpu) / 100f, cpu > 85 ? RED : cpu > 60 ? AMBER : GREEN), vp(dp(CARD_GAP)));
        vitals.addView(r1, new LinearLayout.LayoutParams(-1, dp(VITAL_H)));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-1, dp(VITAL_H));
        p2.setMargins(0, dp(CARD_GAP), 0, 0);
        vitals.addView(r2, p2);
        kicker.setText(Booth.host(this) + ", up " + uptime(m.optInt("uptime_s")));
        if (!state.equalsIgnoreCase("Charging") && !state.equalsIgnoreCase("Full") && bat < 60) {
            kicker.setText(kicker.getText() + ". Not charging: check the phone is plugged in.");
        }
    }

    private LinearLayout.LayoutParams vp(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(left, 0, 0, 0);
        return p;
    }

    private View vital(String label, String value, String sub, float level, int colour) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        Look.Glass g = new Look.Glass(dp(14), 0.8f);
        g.setSmoke(photoSmoke);
        c.setBackground(g);
        c.setPadding(dp(14), dp(8), dp(14), dp(9));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.BOTTOM);
        TextView v = Look.text(this, Look.serif(this), 21, Color.WHITE);
        v.setText(value);
        top.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView l = Look.text(this, Look.sans(this, 600), 12, 0x99FFFFFF);
        l.setText(label);
        top.addView(l, lp(-2, -2, 0, dp(4)));
        c.addView(top);
        TextView s2 = Look.text(this, Look.sans(this, 500), 11, 0x99FFFFFF);
        s2.setText(sub);
        s2.setSingleLine(true);
        s2.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(s2, lp(-1, -2, dp(2), 0));
        View spacer = new View(this);
        c.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        c.addView(new Meter(this, Math.max(0.02f, Math.min(1f, level)), colour, 0x26FFFFFF), lp(-1, dp(3), 0, 0));
        return c;
    }

    private static String fmt1(double v) { return String.format(java.util.Locale.US, "%.1f", v).replace(".0", ""); }

    private static String uptime(int s) {
        if (s >= 86400) return s / 86400 + " d " + (s % 86400) / 3600 + " h";
        if (s >= 3600) return s / 3600 + " h " + (s % 3600) / 60 + " min";
        return Math.max(1, s / 60) + " min";
    }

    // ---- services

    private void renderServices(JSONArray list) {
        services.removeAllViews();
        List<JSONObject> all = arr(list);
        if (all.isEmpty()) { services.addView(quiet("No services reported.")); return; }
        int rows = (all.size() + 1) / 2;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(this);
            for (int c = 0; c < 2; c++) {
                int i = c * rows + r; // fill down the first column, then the second
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1f);
                cp.setMargins(c == 0 ? 0 : dp(CARD_GAP), 0, 0, 0);
                line.addView(i < all.size() ? serviceCell(all.get(i)) : new View(this), cp);
            }
            LinearLayout.LayoutParams lpr = new LinearLayout.LayoutParams(-1, dp(SVC_H));
            lpr.setMargins(0, r == 0 ? 0 : dp(6), 0, 0);
            services.addView(line, lpr);
        }
    }

    /** One service: lamp, name, what it does and its state. Select it for Restart / Stop / Start. */
    private View serviceCell(JSONObject s) {
        String name = s.optString("name"), label = s.optString("label", name);
        boolean running = s.optBoolean("running"), off = s.optBoolean("disabled");
        LinearLayout cell = new LinearLayout(this);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(14), 0, dp(12), 0);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setTag("svc:" + name);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x14FFFFFF);
        cell.setBackground(bg);
        cell.addView(new Lamp(this, running ? GREEN : off ? AMBER : RED, running), new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView n = Look.text(this, Look.sans(this, 600), 14, Color.WHITE);
        n.setText(label);
        n.setSingleLine(true);
        cell.addView(n, lp(-2, -2, 0, 0, 0));
        ((LinearLayout.LayoutParams) n.getLayoutParams()).setMargins(dp(12), 0, 0, 0);
        TextView st = Look.text(this, Look.sans(this, 500), 12, 0x99FFFFFF);
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.END);
        st.setGravity(Gravity.END);
        st.setText(running ? s.optString("role") : off ? "Stopped by you" : "Down");
        if (!running && !off) st.setTextColor(RED);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.setMargins(dp(10), 0, 0, 0);
        cell.addView(st, sp);
        cell.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x14FFFFFF);
            n.setTextColor(has ? 0xFF16161C : Color.WHITE);
            st.setTextColor(has ? 0xB316161C : (!running && !off ? RED : 0x99FFFFFF));
        });
        cell.setOnClickListener(v -> {
            String detail = running ? label + " is running, " + s.optInt("mem_mb") + " MB memory, up " + uptime(s.optInt("uptime_s")) + "."
                    : off ? label + " is stopped. It stays off until you start it." : label + " is down. The phone retries it every 5 minutes.";
            Sheet sh = new Sheet(label, detail);
            if (running) {
                sh.add("Restart", () -> act("Restarting " + label + "…", "/api/services/" + name + "/restart"));
                if (!name.equals("tailscaled")) sh.add("Stop", () -> {
                    Sheet c = new Sheet("Stop " + label + "?", "It stays off until you start it again.");
                    c.add("Stop " + label, () -> act("Stopping " + label + "…", "/api/services/" + name + "/stop"));
                    c.add("Cancel", null);
                    c.show(1); // starts on Cancel
                });
            } else {
                sh.add("Start", () -> act("Starting " + label + "…", "/api/services/" + name + "/start"));
            }
            sh.add("Close", null);
            sh.show(0);
        });
        return cell;
    }

    // ---- requests

    private void renderRequests(JSONArray list) {
        if (requests == null) return; // requests are handled from the Waiting lane
        dropTiles(requests);
        requests.removeAllViews();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            int id = r.optInt("id");
            String title = r.optString("title");
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(8), dp(6));
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setCornerRadius(dp(6));
            ph.setColor(0xFF22222A);
            FrameLayout pf = new FrameLayout(this);
            pf.setBackground(ph);
            Look.roundClip(pf, dp(6));
            pf.addView(img, new FrameLayout.LayoutParams(-1, -1));
            tiles.add(new Object[]{img, r.isNull("poster") ? null : r.optString("poster", null), dp(36) * 2});
            row.addView(pf, new LinearLayout.LayoutParams(dp(36), dp(54)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView n = Look.text(this, Look.sans(this, 600), 14, Color.WHITE);
            n.setText(title + (r.optString("year").isEmpty() ? "" : " (" + r.optString("year") + ")"));
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(n);
            TextView sub = Look.text(this, Look.sans(this, 500), 11, 0x99FFFFFF);
            sub.setSingleLine(true);
            sub.setText((r.optString("kind").equals("tv") ? "Series" : "Film") + (r.isNull("by") || r.optString("by").isEmpty() ? "" : " · " + r.optString("by")));
            text.addView(sub);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.setMargins(dp(12), 0, dp(8), 0);
            row.addView(text, tp);
            TextView ok = pill("Approve", true);
            ok.setTag("req:" + id + ":approve");
            ok.setOnClickListener(v -> act("Approving " + title + "…", "/api/requests/" + id + "/approve"));
            row.addView(ok, lp(dp(74), dp(30), 0, 0, dp(6)));
            TextView no = pill("Decline", false);
            no.setTag("req:" + id + ":decline");
            no.setOnClickListener(v -> act("Declining " + title + "…", "/api/requests/" + id + "/decline"));
            row.addView(no, lp(dp(70), dp(30), 0, 0));
            requests.addView(row, new LinearLayout.LayoutParams(-1, dp(66)));
        }
        if (requests.getChildCount() == 0) requests.addView(quiet("No requests waiting."));
    }

    private TextView quiet(String text) {
        TextView t = Look.text(this, Look.sans(this, 500), 13, 0x8CFFFFFF);
        t.setText(text);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        return t;
    }

    // ---- alerts

    private void renderAlerts(JSONArray list) {
        alerts.removeAllViews();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null) continue;
            String level = a.optString("level");
            LinearLayout row = new LinearLayout(this);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            Look.Glass g = new Look.Glass(dp(16), 0.8f);
            g.setSmoke(photoSmoke);
            row.setBackground(g);
            int colour = level.equals("bad") ? RED : level.equals("good") ? GREEN : AMBER;
            row.addView(new Lamp(this, colour, true), lp(dp(11), dp(11), dp(4), 0, dp(12)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView t = Look.text(this, Look.sans(this, 600), 14, Color.WHITE);
            t.setText(a.optString("title"));
            text.addView(t);
            TextView b = Look.text(this, Look.sans(this, 500), 12, 0xB3FFFFFF);
            b.setText(a.optString("body"));
            b.setMaxLines(3);
            b.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(b, lp(-1, -2, dp(2), 0));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            alerts.addView(row, lp(-1, -2, i == 0 ? 0 : dp(8), 0));
        }
    }

    // ---------------------------------------------------------------- pictures

    private void dropTiles(View parent) {
        for (java.util.Iterator<Object[]> it = tiles.iterator(); it.hasNext(); ) {
            if (isIn(parent, (View) it.next()[0])) it.remove();
        }
    }

    private static boolean isIn(View parent, View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == parent) return true;
        return false;
    }

    /** Fill every poster view that has none: from the held bitmaps, or fetched and decoded off the main thread. */
    private void loadPictures() {
        final int my = gen;
        for (Object[] t : tiles) {
            ImageView v = (ImageView) t[0];
            String path = (String) t[1];
            if (path == null || v.getDrawable() != null) continue;
            Bitmap held = mem.get(path);
            if (held != null && !held.isRecycled()) { put(v, held); continue; }
            int w = (Integer) t[2];
            io.execute(() -> {
                Bitmap bm = Booth.image(this, path, w);
                if (bm == null) return;
                runOnUiThread(() -> {
                    if (my != gen || isDestroyed()) { bm.recycle(); return; }
                    Bitmap old = mem.put(path, bm);
                    if (old != null && old != bm && !isUsed(old)) old.recycle();
                    put(v, bm);
                });
            });
        }
    }

    private boolean isUsed(Bitmap b) {
        for (Object[] t : tiles) {
            android.graphics.drawable.Drawable d = ((ImageView) t[0]).getDrawable();
            if (d instanceof android.graphics.drawable.BitmapDrawable && ((android.graphics.drawable.BitmapDrawable) d).getBitmap() == b) return true;
        }
        return false;
    }

    private void put(ImageView v, Bitmap bm) {
        v.setImageBitmap(bm);
        if (v.getTag() instanceof View) ((View) v.getTag()).setVisibility(View.GONE);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(Look.ms(this, 220)).start();
    }

    // ---------------------------------------------------------------- actions

    private String busyNote;

    /** POST an action, say what is happening, then look again. */
    private void act(String doing, String path) {
        busyNote = doing;
        status.setText(doing);
        io.execute(() -> {
            String err = null;
            int code = 0;
            try {
                Booth.post(this, path);
            } catch (Booth.Failure f) {
                err = f.getMessage();
                code = f.code;
            } catch (IOException e) {
                err = "Can't reach Booth.";
            }
            String e = err;
            int c = code;
            runOnUiThread(() -> {
                busyNote = null;
                if (isDestroyed() || !dashboard) return;
                if (c == 401) { showSignIn("Signed out. Sign in again."); return; }
                status.setText(e == null ? "Done." : e);
                loading = false;
                refresh();
                if (e == null) handler.postDelayed(() -> { if (!isDestroyed()) refresh(); }, 4000); // services take a moment to settle
            });
        });
    }

    // ---------------------------------------------------------------- the action sheet

    /** A small glass card of choices over the dashboard. Back closes it. */
    private final class Sheet {
        private final LinearLayout card = new LinearLayout(BoothActivity.this);
        private final List<TextView> rows = new ArrayList<>();

        Sheet(String title, String body) {
            card.setOrientation(LinearLayout.VERTICAL);
            Look.Glass g = new Look.Glass(dp(24), 0.95f);
            g.setSmoke(0.92f);
            card.setBackground(g);
            card.setPadding(dp(22), dp(20), dp(22), dp(18));
            TextView t = Look.text(BoothActivity.this, Look.serif(BoothActivity.this), 24, Color.WHITE);
            t.setText(title);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(t);
            TextView b = Look.text(BoothActivity.this, Look.sans(BoothActivity.this, 500), 13, 0xB3FFFFFF);
            b.setText(body);
            b.setLineSpacing(dp(3), 1f);
            card.addView(b, lp(-1, -2, dp(6), dp(14)));
        }

        void add(String label, Runnable action) {
            TextView p = pill(label, false);
            p.setOnClickListener(v -> { closeSheet(); if (action != null) action.run(); });
            rows.add(p);
            card.addView(p, lp(-1, dp(38), rows.size() == 1 ? 0 : dp(8), 0));
        }

        void show(int focus) {
            beforeSheet = getCurrentFocus();
            sheet = new FrameLayout(BoothActivity.this);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(380), -2, Gravity.CENTER);
            sheet.addView(card, p);
            root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
            card.setAlpha(0f);
            card.setTranslationY(dp(12));
            card.animate().alpha(1f).translationY(0f).setDuration(Look.ms(BoothActivity.this, 240)).setInterpolator(new DecelerateInterpolator(2f)).start();
            // Focus stays inside the sheet: the dashboard behind can't take it.
            ((android.view.ViewGroup) screen).setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            rows.get(Math.min(focus, rows.size() - 1)).requestFocus();
            screen.setAlpha(0.35f);
        }
    }

    private void closeSheet() {
        if (sheet == null) return;
        root.removeView(sheet);
        sheet = null;
        screen.setAlpha(1f);
        ((android.view.ViewGroup) screen).setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (beforeSheet != null && beforeSheet.isAttachedToWindow()) beforeSheet.requestFocus();
        else if (beforeSheet != null && beforeSheet.getTag() != null) {
            View v = findTagged(screen, beforeSheet.getTag());
            if (v != null) v.requestFocus();
        }
    }

    // ---------------------------------------------------------------- small views

    /** A status lamp: a dot, with a soft halo when lit. */
    private static final class Lamp extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int colour;
        private final boolean lit;

        Lamp(Context c, int colour, boolean lit) {
            super(c);
            this.colour = colour;
            this.lit = lit;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            if (lit) {
                // Halo within the view's own bounds: no clip tricks, no extra layer.
                p.setShader(new RadialGradient(w / 2f, h / 2f, r, new int[]{colour, colour, colour & 0x00FFFFFF},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                cv.drawCircle(w / 2f, h / 2f, r, p);
                p.setShader(null);
            } else {
                p.setColor(colour & 0x00FFFFFF | 0x66000000);
                cv.drawCircle(w / 2f, h / 2f, r * 0.6f, p);
            }
        }
    }

    /** A thin horizontal meter: a track and a rounded fill. */
    private static final class Meter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float level;
        private final int fill, track;

        Meter(Context c, float level, int fill, int track) {
            super(c);
            this.level = level;
            this.fill = fill;
            this.track = track;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            p.setColor(track);
            cv.drawRoundRect(r, h / 2f, h / 2f, p);
            r.set(0, 0, Math.max(h, w * Math.max(0f, Math.min(1f, level))), h);
            p.setColor(fill);
            cv.drawRoundRect(r, h / 2f, h / 2f, p);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, top, 0, bottom);
        return p;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int top, int bottom, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, top, right, bottom);
        return p;
    }

    private int dp(float v) { return Look.dp(this, v); }
}
