package dev.bhawesh.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The room's lights, laid out like Customise home: a preview on the left where the light glows in
 * its colour, and on the right a glass list the remote drives (left and right change a value, OK
 * switches). Changes show at once and reach the bulb a beat later, so a held key doesn't flood it.
 */
public class LightsActivity extends Activity {
    private static final int[][] COLOURS = {
            {255, 196, 137}, {255, 120, 64}, {255, 64, 96}, {214, 72, 255}, {120, 96, 255},
            {48, 160, 255}, {0, 210, 190}, {96, 230, 96}, {255, 220, 64}, {255, 255, 255},
    };
    private static final String[] COLOUR_NAMES = {"Warm glow", "Amber", "Rose", "Magenta", "Violet",
            "Blue", "Teal", "Green", "Gold", "White"};
    private static final int POWER = 0, BRIGHT = 1, WARMTH = 2, COLOUR = 3, SCENE = 4, DIM = 5, MATCH = 6, LOOK = 7;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flush = this::flush;
    private JSONObject pending = new JSONObject();
    private SkyView sky;
    private Glow glow;
    private TextView status, aboutTitle, about;
    private LinearLayout list;
    private View puck;
    private final List<Runnable> refreshers = new ArrayList<>();

    private boolean loaded, hasBulb, on, dimWhilePlaying, matchHome;
    private int dimming = 100, temp = 2700, colour = -1, scene = -1;
    private String via = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sky = new SkyView(this);
        sky.setLook(HomeSettings.get(this, HomeSettings.LOOK));
        sky.setAccent(MainActivity.accent, 0.4f, false);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        // A settings screen, read not admired: the sky dimmed well down, more so over a photo.
        boolean photo = HomeSettings.get(this, HomeSettings.LOOK) == SkyView.WALLPAPER;
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, photo
                ? new int[]{0xE0000000, 0xB3000000, 0xC7000000} : new int[]{0xB3000000, 0x8C000000, 0x99000000}));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        View left = buildLeft();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(380), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.setMargins(dp(Look.PAD_X), 0, 0, 0);
        root.addView(left, lp);
        FrameLayout panel = buildPanel(photo);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(400), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        rp.setMargins(0, dp(28), dp(Look.PAD_X), dp(28));
        root.addView(panel, rp);
        setContentView(root);

        View[] parts = {left, panel};
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlpha(0f);
            parts[i].setTranslationY(dp(14));
            parts[i].animate().alpha(1f).translationY(0f).setStartDelay(Look.ms(this, 80L * i)).setDuration(Look.ms(this, 460))
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        load(false);
    }

    // ---------------------------------------------------------------- layout

    private View buildLeft() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = Look.text(this, Look.sans(this, 600), 13, 0x8CFFFFFF);
        kicker.setText("Room");
        col.addView(kicker);
        TextView title = Look.text(this, Look.serif(this), 40, Color.WHITE);
        title.setText("Lights");
        col.addView(title, lp(-2, -2, dp(4), dp(20)));

        FrameLayout card = new MainActivity.AspectFrame(this);
        Look.roundClip(card, dp(16));
        glow = new Glow(this);
        card.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        status = Look.text(this, Look.sans(this, 600), 14, Color.WHITE);
        status.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        sp.setMargins(dp(16), 0, 0, dp(14));
        card.addView(status, sp);
        View rim = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(dp(16));
        rg.setStroke(dp(1), 0x40FFFFFF);
        rim.setBackground(rg);
        card.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));

        aboutTitle = Look.text(this, Look.sans(this, 600), 16, Color.WHITE);
        col.addView(aboutTitle, lp(-2, -2, dp(22), 0));
        about = Look.text(this, Look.sans(this, 400), 14, 0xA6FFFFFF);
        about.setLineSpacing(dp(4), 1f);
        about.setMinLines(3);
        col.addView(about, lp(-1, -2, dp(6), 0));
        return col;
    }

    private FrameLayout buildPanel(boolean photo) {
        FrameLayout panel = new FrameLayout(this);
        Look.Glass glass = new Look.Glass(dp(24), 0.8f);
        glass.setSmoke(photo ? 0.8f : 0f);
        panel.setBackground(glass);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout inner = new FrameLayout(this);
        puck = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(14));
        pg.setColor(0xF2FFFFFF);
        puck.setBackground(pg);
        puck.setAlpha(0f);
        inner.addView(puck, new FrameLayout.LayoutParams(-1, dp(46)));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        inner.addView(list, new FrameLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(inner);
        panel.addView(sv, new FrameLayout.LayoutParams(-1, -2));
        return panel;
    }

    /** The rows for what's known: all of them with a bulb, or just "Look again" without. */
    private void buildRows() {
        View had = getCurrentFocus();
        int at = had == null ? 0 : Math.max(0, list.indexOfChild(had));
        list.removeAllViews();
        refreshers.clear();
        if (hasBulb || !loaded) {
            header("Light");
            row(POWER, "Power", "Switch the light on or off.");
            row(BRIGHT, "Brightness", "Left and right in steps of ten.");
            row(WARMTH, "Warmth", "From candle-warm 2200 K to cool daylight 6500 K. Choosing a warmth leaves colour and scene.");
            row(COLOUR, "Colour", "Left and right step through ten colours.");
            row(SCENE, "Scene", "WiZ's own scenes: Cozy, TV time, Candlelight, Sunset, Ocean and more. Some move on their own.");
            header("Automatic");
            row(DIM, "Dim while playing", "When a show or video starts, the light fades low and warm. It comes back as it was when it stops.");
            row(MATCH, "Match the home", "While you browse the home, the light takes the colour of the app you're on, softly.");
        }
        header("Lights");
        row(LOOK, "Look again", "Asks every address on this Wi-Fi for WiZ bulbs, or for Lumen if the TV can't reach them itself.");
        refresh();
        View first = list.getChildAt(Math.min(Math.max(at, 1), list.getChildCount() - 1));
        if (!first.isFocusable()) first = list.getChildAt(1);
        first.requestFocus();
    }

    private void header(String text) {
        TextView h = Look.text(this, Look.sans(this, 600), 12, 0x80FFFFFF);
        h.setText(text);
        LinearLayout.LayoutParams p = lp(-2, -2, list.getChildCount() == 0 ? dp(6) : dp(16), dp(6));
        p.setMargins(dp(16), p.topMargin, 0, p.bottomMargin);
        list.addView(h, p);
    }

    private void row(int kind, String name, String hint) {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setPadding(dp(16), 0, dp(16), 0);
        TextView label = Look.text(this, Look.sans(this, 500), 15, Color.WHITE);
        label.setText(name);
        v.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        HomeSettings.Toggle toggle = kind == POWER || kind == DIM || kind == MATCH ? new HomeSettings.Toggle(this) : null;
        TextView value = Look.text(this, Look.sans(this, 500), 14, 0xB3FFFFFF);
        HomeSettings.Dots dots = kind == BRIGHT ? new HomeSettings.Dots(this, 10) : null;
        WarmBar bar = kind == WARMTH ? new WarmBar(this) : null;
        View swatch = kind == COLOUR ? new View(this) : null;
        GradientDrawable sw = new GradientDrawable();
        sw.setShape(GradientDrawable.OVAL);
        if (swatch != null) swatch.setBackground(sw);
        if (toggle != null) v.addView(toggle, new LinearLayout.LayoutParams(dp(36), dp(22)));
        else {
            v.addView(value);
            if (dots != null) v.addView(dots, lp2(dp(6 * 1.8f * 10), dp(6), dp(12)));
            if (bar != null) v.addView(bar, lp2(dp(72), dp(6), dp(12)));
            if (swatch != null) v.addView(swatch, lp2(dp(12), dp(12), dp(10)));
        }
        Runnable show = () -> {
            boolean ready = loaded && (hasBulb || kind == LOOK);
            v.setAlpha(ready || kind == LOOK ? 1f : 0.45f);
            if (toggle != null) toggle.set(kind == POWER ? on : kind == DIM ? dimWhilePlaying : matchHome);
            switch (kind) {
                case BRIGHT: value.setText(loaded ? dimming + "%" : "…"); dots.set(Math.max(0, dimming / 10 - 1)); break;
                case WARMTH: value.setText(loaded ? temp + " K" : "…"); bar.set(temp); break;
                case COLOUR:
                    value.setText(colour < 0 ? "None" : COLOUR_NAMES[colour]);
                    swatch.setVisibility(colour < 0 ? View.GONE : View.VISIBLE);
                    if (colour >= 0) sw.setColor(rgb(COLOURS[colour]));
                    break;
                case SCENE: value.setText(scene < 0 ? "None" : (String) Lights.SCENES[scene][1]); break;
                case LOOK: value.setText(!loaded ? "Looking…" : via.isEmpty() ? "None found" : via); break;
                default: break;
            }
        };
        refreshers.add(show);

        v.setOnFocusChangeListener((x, has) -> {
            label.setTextColor(has ? 0xFF16161C : Color.WHITE);
            value.setTextColor(has ? 0xCC16161C : 0xB3FFFFFF);
            if (toggle != null) toggle.setInk(has);
            if (dots != null) dots.setInk(has);
            if (bar != null) bar.setInk(has);
            if (!has) return;
            movePuck(v);
            aboutTitle.setText(name);
            about.setText(hint);
            about.setAlpha(0f);
            about.animate().alpha(1f).setDuration(Look.ms(this, 260)).start();
        });
        v.setOnKeyListener((x, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            int step = code == KeyEvent.KEYCODE_DPAD_RIGHT || ok ? 1 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (step == 0) return false;
            if (kind == LOOK) { if (ok) load(true); return true; }
            if (!loaded || !hasBulb) return true;
            change(kind, step);
            if (toggle == null) {
                value.setTranslationX(dp(8) * step);
                value.setAlpha(0.3f);
                value.animate().translationX(0f).alpha(1f).setDuration(Look.ms(this, 220)).start();
            }
            return true;
        });
        list.addView(v, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void change(int kind, int step) {
        switch (kind) {
            case POWER: on = !on; send("on", on); break;
            case BRIGHT: dimming = Math.max(10, Math.min(100, dimming + step * 10)); send("dimming", dimming); break;
            case WARMTH:
                temp = Math.max(2200, Math.min(6500, temp + step * 300));
                colour = -1; scene = -1;
                send("temp", temp);
                break;
            case COLOUR:
                colour = ((colour < 0 ? (step > 0 ? -1 : 0) : colour) + step + COLOURS.length) % COLOURS.length;
                scene = -1;
                send("rgb", new JSONArray().put(COLOURS[colour][0]).put(COLOURS[colour][1]).put(COLOURS[colour][2]));
                break;
            case SCENE:
                scene = ((scene < 0 ? (step > 0 ? -1 : 0) : scene) + step + Lights.SCENES.length) % Lights.SCENES.length;
                colour = -1;
                send("scene", Lights.SCENES[scene][0]);
                break;
            case DIM: dimWhilePlaying = !dimWhilePlaying; auto("dimWhilePlaying", dimWhilePlaying); break;
            case MATCH: matchHome = !matchHome; auto("matchHome", matchHome); break;
            default: break;
        }
        refresh();
    }

    private void movePuck(View row) {
        if (!row.isLaidOut()) { row.post(() -> { if (row.hasFocus()) movePuck(row); }); return; }
        boolean first = puck.getAlpha() == 0f;
        float y = row.getTop();
        if (first) {
            puck.setTranslationY(y);
            puck.animate().alpha(1f).setDuration(Look.ms(this, 200)).start();
        } else {
            puck.animate().translationY(y).setDuration(Look.ms(this, 260))
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f)).start();
        }
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
        glow.set(!loaded ? -1 : !hasBulb || !on ? 0 : currentColour(), dimming);
        status.setText(!loaded ? "Looking for lights…" : !hasBulb ? "No lights found" : !on ? "Off"
                : (scene >= 0 ? (String) Lights.SCENES[scene][1] : colour >= 0 ? COLOUR_NAMES[colour] : temp + " K white") + " · " + dimming + "%");
    }

    private int currentColour() {
        return colour >= 0 ? rgb(COLOURS[colour]) : scene >= 0 ? 0xFFFFB878 : WarmBar.warmth(temp);
    }

    // ---------------------------------------------------------------- talking to the TV's Lights

    private void load(boolean sweep) {
        loaded = false;
        buildRows();
        Lights.async(this, () -> sweep || !Lights.available(this) ? Lights.discover(this) : Lights.status(this), this::show);
    }

    private void show(JSONObject s) {
        if (isDestroyed()) return;
        loaded = true;
        JSONArray bulbs = s == null ? null : s.optJSONArray("bulbs");
        JSONObject b = null;
        for (int i = 0; bulbs != null && i < bulbs.length(); i++) {
            JSONObject x = bulbs.optJSONObject(i);
            if (x != null && x.optBoolean("online")) { b = x; break; }
        }
        hasBulb = b != null;
        JSONObject auto = s == null ? null : s.optJSONObject("auto");
        dimWhilePlaying = auto != null && auto.optBoolean("dimWhilePlaying");
        matchHome = auto != null && auto.optBoolean("matchHome");
        String how = s == null ? "" : s.optString("via", "");
        via = !hasBulb ? "" : how.equals("lumen") ? "Through Lumen" : "Direct";
        if (b != null) {
            on = b.optBoolean("on");
            dimming = b.optInt("dimming", 100);
            if (!b.isNull("temp")) temp = b.optInt("temp");
            scene = b.isNull("scene") ? -1 : sceneIndex(b.optInt("scene"));
            JSONArray rgb = b.optJSONArray("rgb");
            colour = rgb == null ? -1 : nearestColour(rgb.optInt(0), rgb.optInt(1), rgb.optInt(2));
        }
        buildRows();
    }

    private void send(String key, Object value) {
        try {
            if (key.equals("on")) pending = new JSONObject(); // power wins over queued steps
            pending.put(key, value);
            if (!key.equals("on")) { pending.put("on", true); on = true; }
        } catch (Exception ignored) { }
        handler.removeCallbacks(flush);
        handler.postDelayed(flush, 150);
    }

    private void flush() {
        JSONObject p = pending;
        pending = new JSONObject();
        if (p.length() > 0) Lights.async(this, () -> Lights.set(this, p), s -> { });
    }

    private void auto(String key, boolean value) {
        Lights.async(this, () -> Lights.setAuto(this, new JSONObject().put(key, value)), s -> { });
    }

    @Override protected void onResume() { super.onResume(); sky.setRunning(true); }

    @Override protected void onPause() {
        super.onPause();
        sky.setRunning(false);
        handler.removeCallbacks(flush);
        flush();
    }

    // ---------------------------------------------------------------- drawing

    /** The preview: a dark room with the light's glow in it, breathing while it looks. */
    private static final class Glow extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Look.Icon bulb;
        private int colour = -1, level = 100;
        private float breath = 1f;
        private android.animation.ValueAnimator anim;

        Glow(Context c) {
            super(c);
            bulb = new Look.Icon(Look.Icon.BULB, Look.dp(c, 34));
        }

        /** colour -1 while looking, 0 for off or nothing found. */
        void set(int colour, int level) {
            this.colour = colour;
            this.level = level;
            if (colour == -1 && anim == null) {
                anim = android.animation.ValueAnimator.ofFloat(0.55f, 1f).setDuration(1400);
                anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                anim.addUpdateListener(a -> { breath = (float) a.getAnimatedValue(); invalidate(); });
                anim.start();
            } else if (colour != -1 && anim != null) {
                anim.cancel();
                anim = null;
                breath = 1f;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF14141B, 0xFF0A0A0E, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            int col = colour == -1 ? 0xFFFFE6C8 : colour == 0 ? 0xFF4A4A55 : colour;
            float strength = colour == -1 ? 0.55f * breath : colour == 0 ? 0.25f : 0.35f + 0.65f * level / 100f;
            float cx = w / 2f, cy = h / 2f, r = h * 0.95f;
            float coreA = 235 * strength / 255f;
            int core = (Math.round(255 * coreA) << 24) | (col & 0xFFFFFF);
            int mid = (Math.round(90 * strength) << 24) | (col & 0xFFFFFF);
            p.setShader(new RadialGradient(cx, cy, r, new int[]{core, mid, col & 0xFFFFFF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
            // The icon reads against what's behind it: the glow's colour over the dark room.
            float lum = (0.2126f * Color.red(col) + 0.7152f * Color.green(col) + 0.0722f * Color.blue(col)) / 255f;
            float behind = 0.08f + (lum - 0.08f) * coreA;
            int s = bulb.getIntrinsicWidth();
            bulb.setColor(behind > 0.45f ? 0xE616161C : 0xE6FFFFFF);
            bulb.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f), Math.round(cy + s / 2f));
            bulb.draw(cv);
        }
    }

    /** Warmth as a short amber-to-blue-white track with a knob. */
    static final class WarmBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int temp = 2700;
        private boolean ink;

        WarmBar(Context c) { super(c); }
        void set(int k) { temp = k; invalidate(); }
        void setInk(boolean dark) { ink = dark; invalidate(); }

        static int warmth(int k) {
            float t = Math.max(0f, Math.min(1f, (k - 2200) / 4300f));
            int warm = 0xFFFFA954, mid = 0xFFFFF1DE, cool = 0xFFCFE0FF;
            return t < 0.5f ? mix(warm, mid, t * 2) : mix(mid, cool, (t - 0.5f) * 2);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            p.setShader(new LinearGradient(0, 0, w, 0, new int[]{warmth(2200), warmth(4350), warmth(6500)}, null, Shader.TileMode.CLAMP));
            cv.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            p.setColor(ink ? 0xFF16161C : Color.WHITE);
            float f = (temp - 2200) / 4300f;
            cv.drawCircle(Math.max(rad, Math.min(w - rad, w * f)), h / 2f, h * 0.9f, p);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int rgb(int[] c) { return Color.rgb(c[0], c[1], c[2]); }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int nearestColour(int r, int g, int b) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < COLOURS.length; i++) {
            long dr = r - COLOURS[i][0], dg = g - COLOURS[i][1], db = b - COLOURS[i][2];
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static int sceneIndex(int id) {
        for (int i = 0; i < Lights.SCENES.length; i++) if ((Integer) Lights.SCENES[i][0] == id) return i;
        return -1;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, top, 0, bottom);
        return p;
    }

    private static LinearLayout.LayoutParams lp2(int w, int h, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(left, 0, 0, 0);
        return p;
    }

    private int dp(float v) { return Look.dp(this, v); }
}
