package dev.bhawesh.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Customise home": how the home screen looks. Each row is one setting; Left/Right (or OK)
 * steps through its values, saved at once, with the background previewed live behind.
 */
public class HomeSettings extends Activity {

    // ---------------------------------------------------------------- stored values

    static final String STYLE = "style", LOOK = "look", RECENT = "rows_recent", TRENDING = "rows_trending",
            MOTION = "motion", WEATHER = "weather", SAVER = "screensaver", NAV = "nav_icons";
    static final int STYLE_FEATURED = 0, STYLE_MINIMAL = 1;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static int get(Context c, String key) {
        // Defaults: featured titles, live sky, both rows on, full motion, weather on, clock screensaver.
        int def = key.equals(RECENT) || key.equals(TRENDING) || key.equals(WEATHER) || key.equals(SAVER) ? 1 : 0;
        return prefs(c).getInt(key, def);
    }

    /** Bumped on every change so the home screen knows to rebuild. */
    static int version(Context c) { return prefs(c).getInt("settings_version", 0); }

    // ---------------------------------------------------------------- screen

    private static final class Row {
        final String key, label, hint;
        final String[] values;
        Row(String key, String label, String hint, String... values) {
            this.key = key; this.label = label; this.hint = hint; this.values = values;
        }
        boolean toggle() { return values.length == 2 && values[1].equals("Shown"); }
    }

    /** Group header, then its rows. */
    private static final Object[] LIST = {
            "Look",
            new Row(STYLE, "Home style", "Featured titles shows what's trending on your apps above them. Minimal keeps just your apps over the background.",
                    "Featured titles", "Minimal"),
            new Row(LOOK, "Background", "Live sky follows the time of day, from dawn peach to night indigo. The colour washes stay fixed. Wallpaper shows the picture you choose in the phone app.",
                    SkyView.LOOKS),
            new Row(NAV, "Top bar", "Labels names each button. Icons only keeps the bar small, and the name shows under the button you're on.",
                    "Labels", "Icons only"),
            "Home rows",
            new Row(RECENT, "Recently watched", "What you've played lately, with the app it was on. Hold OK on a title there to remove it.", "Hidden", "Shown"),
            new Row(TRENDING, "Trending rows", "A row for each of your apps with what's popular on it right now.", "Hidden", "Shown"),
            new Row(WEATHER, "Weather", "Temperature and conditions next to the date, for where the TV is.", "Hidden", "Shown"),
            "Feel",
            new Row(MOTION, "Motion", "Calm drops the light streaks and springy lifts for quieter, quicker focus moves.", "Full", "Calm"),
            new Row(SAVER, "Screensaver", "What shows when the TV sits idle: a clock, trending posters, your photos, the sky, or a different one each time.",
                    Screensaver.STYLES),
    };

    private SkyView sky, previewSky;
    private View dim;
    private Look.Glass panelGlass;
    private Preview preview;
    private TextView about, aboutTitle;
    private View puck;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sky = new SkyView(this);
        sky.setLook(get(this, LOOK));
        sky.setAccent(MainActivity.accent, 0.4f, false);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        // Settings are read, not admired: dim the sky so the text carries.
        dim = new View(this);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildLeft(), leftLp());
        FrameLayout list = buildList();
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(400), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        rp.setMargins(0, 0, dp(Look.PAD_X), 0);
        root.addView(list, rp);
        setContentView(root);
        legible(get(this, LOOK));

        View[] parts = {root.getChildAt(2), list};
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlpha(0f);
            parts[i].setTranslationY(dp(14));
            parts[i].animate().alpha(1f).translationY(0f).setStartDelay(Look.ms(this, 80L * i)).setDuration(Look.ms(this, 460))
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private FrameLayout.LayoutParams leftLp() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(380), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        p.setMargins(dp(Look.PAD_X), 0, 0, 0);
        return p;
    }

    /** Title, a live miniature of the home, and what the focused setting does. */
    private View buildLeft() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = Look.text(this, Look.sans(this, 600), 13, 0x8CFFFFFF);
        kicker.setText("Home screen");
        col.addView(kicker);
        TextView title = Look.text(this, Look.serif(this), 40, Color.WHITE);
        title.setText("Customise home");
        col.addView(title, lp(-2, -2, 0, dp(4), 0, dp(20)));

        FrameLayout card = new MainActivity.AspectFrame(this);
        Look.roundClip(card, dp(16));
        previewSky = new SkyView(this);
        previewSky.setLook(get(this, LOOK));
        previewSky.setAccent(MainActivity.accent, 0.5f, false);
        card.addView(previewSky, new FrameLayout.LayoutParams(-1, -1));
        preview = new Preview(this);
        card.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        View rim = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(dp(16));
        rg.setStroke(dp(1), 0x40FFFFFF);
        rim.setBackground(rg);
        card.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));

        aboutTitle = Look.text(this, Look.sans(this, 600), 16, Color.WHITE);
        col.addView(aboutTitle, lp(-2, -2, 0, dp(22), 0, 0));
        about = Look.text(this, Look.sans(this, 400), 14, 0xA6FFFFFF);
        about.setLineSpacing(dp(4), 1f);
        about.setMinLines(3);
        col.addView(about, lp(-1, -2, 0, dp(6), 0, 0));
        return col;
    }

    /** One glass panel: group headers and rows, with a white highlight that slides to the focused row. */
    private FrameLayout buildList() {
        FrameLayout panel = new FrameLayout(this);
        panelGlass = new Look.Glass(dp(24), 0.8f);
        panel.setBackground(panelGlass);
        int pad = dp(10);
        panel.setPadding(pad, pad, pad, pad);
        puck = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(14));
        pg.setColor(0xF2FFFFFF);
        puck.setBackground(pg);
        puck.setAlpha(0f);
        panel.addView(puck, new FrameLayout.LayoutParams(-1, dp(46)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        View first = null;
        for (Object o : LIST) {
            if (o instanceof String) {
                TextView h = Look.text(this, Look.sans(this, 600), 12, 0x80FFFFFF);
                h.setText((String) o);
                list.addView(h, lp(-2, -2, dp(16), list.getChildCount() == 0 ? dp(6) : dp(16), 0, dp(6)));
            } else {
                View v = row((Row) o);
                list.addView(v, new LinearLayout.LayoutParams(-1, dp(46)));
                if (first == null) first = v;
            }
        }
        panel.addView(list, new FrameLayout.LayoutParams(-1, -2));
        if (first != null) first.requestFocus();
        return panel;
    }

    private View row(Row r) {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setPadding(dp(16), 0, dp(16), 0);

        TextView label = Look.text(this, Look.sans(this, 500), 15, Color.WHITE);
        label.setText(r.label);
        v.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        Toggle toggle = r.toggle() ? new Toggle(this) : null;
        TextView value = Look.text(this, Look.sans(this, 500), 14, 0xB3FFFFFF);
        Dots dots = toggle == null ? new Dots(this, r.values.length) : null;
        if (toggle != null) {
            v.addView(toggle, new LinearLayout.LayoutParams(dp(36), dp(22)));
        } else {
            v.addView(value);
            v.addView(dots, lp(dp(6 * 1.8f * r.values.length), dp(6), dp(12), 0, 0, 0));
        }
        Runnable show = () -> {
            int i = get(this, r.key);
            if (toggle != null) toggle.set(i == 1);
            else { value.setText(r.values[i]); dots.set(i); }
        };
        show.run();

        v.setOnFocusChangeListener((x, has) -> {
            int ink = has ? 0xFF16161C : Color.WHITE;
            label.setTextColor(ink);
            value.setTextColor(has ? 0xCC16161C : 0xB3FFFFFF);
            if (toggle != null) toggle.setInk(has);
            if (dots != null) dots.setInk(has);
            if (!has) return;
            movePuck(v);
            preview.show(r.key);
            aboutTitle.setText(r.label);
            about.setText(r.hint);
            about.setAlpha(0f);
            about.animate().alpha(1f).setDuration(Look.ms(this, 260)).start();
        });
        v.setOnKeyListener((x, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER ? 1 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (step == 0) return false;
            int n = r.values.length, next = ((get(this, r.key) + step) % n + n) % n;
            prefs(this).edit().putInt(r.key, next).putInt("settings_version", version(this) + 1).apply();
            show.run();
            if (toggle == null) {
                value.setTranslationX(dp(8) * step);
                value.setAlpha(0.3f);
                value.animate().translationX(0f).alpha(1f).setDuration(Look.ms(this, 220)).start();
            }
            if (r.key.equals(LOOK)) { sky.setLook(next); previewSky.setLook(next); legible(next); }
            if (r.key.equals(STYLE) || r.key.equals(RECENT) || r.key.equals(WEATHER)) preview.invalidate();
            if (r.key.equals(SAVER)) Screensaver.apply(this);
            if (r.key.equals(MOTION) || r.key.equals(SAVER)) preview.restart();
            return true;
        });
        return v;
    }

    private void movePuck(View row) {
        // First focus comes before layout (top is still 0): place the highlight once it's laid out.
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

    /** Over a wallpaper the sky is a photo: dim it more and smoke the panel so the settings read. */
    private void legible(int look) {
        boolean photo = look == SkyView.WALLPAPER;
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, photo
                ? new int[]{0xE0000000, 0xB3000000, 0xC7000000} : new int[]{0xB3000000, 0x8C000000, 0x99000000}));
        panelGlass.setSmoke(photo ? 0.8f : 0f);
    }

    @Override protected void onResume() { super.onResume(); sky.setRunning(true); previewSky.setRunning(true); }
    @Override protected void onPause() { super.onPause(); sky.setRunning(false); previewSky.setRunning(false); }

    private int dp(float v) { return Look.dp(this, v); }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    // ---------------------------------------------------------------- little views

    /**
     * The home in miniature, drawn over the preview sky: top bar, featured block or big name, apps.
     * On the Motion row the focus walks along the apps the way it does at home (Full: springy lift
     * and a streak of light; Calm: a quick settle). On the Screensaver row it shows that style,
     * with real posters and photos decoded small.
     */
    private final class Preview extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint img = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final android.graphics.Path clip = new android.graphics.Path();
        private final java.util.List<android.graphics.Bitmap> posters = new java.util.ArrayList<>(), photos = new java.util.ArrayList<>();
        private final java.util.List<String> posterNames = new java.util.ArrayList<>();
        private String mode = "";
        private long since;
        private boolean loading;
        Preview(Context c) { super(c); }

        /** The focused row's key; Motion and Screensaver get their own preview. */
        void show(String key) {
            String m = key.equals(MOTION) || key.equals(SAVER) ? key : "";
            if (!m.equals(mode)) { mode = m; restart(); }
            if (m.equals(SAVER)) load();
        }

        void restart() { since = android.os.SystemClock.uptimeMillis(); invalidate(); }

        private long t() { return android.os.SystemClock.uptimeMillis() - since; }

        private void bar(android.graphics.Canvas c, float x, float y, float w, float h, int color) {
            p.setColor(color);
            r.set(x, y, x + w, y + h);
            c.drawRoundRect(r, h / 2, h / 2, p);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (mode.equals(SAVER)) { saver(c); }
            else home(c);
            // ponytail: redraws every frame while a moving preview shows; it's one small card.
            if (!mode.isEmpty()) postInvalidateOnAnimation();
        }

        private void home(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), x0 = w * 0.06f;
            boolean minimal = get(getContext(), STYLE) == STYLE_MINIMAL;
            // top bar: time, date line, nav capsule
            bar(c, x0, h * 0.07f, w * 0.1f, h * 0.07f, 0xE6FFFFFF);
            bar(c, x0 + w * 0.13f, h * 0.085f, w * 0.14f, h * 0.035f, 0x80FFFFFF);
            if (get(getContext(), WEATHER) == 1) bar(c, x0 + w * 0.29f, h * 0.085f, w * 0.07f, h * 0.035f, 0x59FFFFFF);
            bar(c, w * 0.76f, h * 0.07f, w * 0.18f, h * 0.06f, 0x40FFFFFF);
            if (minimal) {
                bar(c, x0, h * 0.56f, w * 0.22f, h * 0.08f, 0xF2FFFFFF);
            } else {
                // picture on the right, fading left
                p.setShader(new android.graphics.LinearGradient(w * 0.38f, 0, w * 0.7f, 0, 0x00FFFFFF, 0x40FFFFFF,
                        android.graphics.Shader.TileMode.CLAMP));
                r.set(w * 0.38f, 0, w, h * 0.66f);
                c.drawRect(r, p);
                p.setShader(null);
                bar(c, x0, h * 0.26f, w * 0.1f, h * 0.03f, 0x80FFFFFF);
                bar(c, x0, h * 0.32f, w * 0.3f, h * 0.075f, 0xF2FFFFFF);
                bar(c, x0, h * 0.44f, w * 0.34f, h * 0.03f, 0x66FFFFFF);
                bar(c, x0, h * 0.49f, w * 0.28f, h * 0.03f, 0x66FFFFFF);
                bar(c, x0, h * 0.56f, w * 0.13f, h * 0.06f, 0x59FFFFFF);
            }
            // apps row; on the Motion row the focus steps right every STEP ms, as at home
            final long STEP = 1300;
            boolean moving = mode.equals(MOTION), calm = get(getContext(), MOTION) == 1;
            long t = t();
            int cur = moving ? (int) (t / STEP % 6) : 0, prev = (cur + 5) % 6;
            float ph = moving ? t % STEP : 1e6f;
            float tw = w * 0.155f, gap = w * 0.02f, rad = h * 0.02f;
            for (int i = 0; i < 6; i++) {
                float k = 1f;
                if (i == cur) k = 1f + 0.08f * (calm ? decel(ph / 150f, 2f) : overshoot(ph / 460f, 1.5f));
                else if (i == prev && moving && t >= STEP) k = 1f + 0.08f * (1f - decel(ph / (calm ? 140f : 260f), 2f));
                float cx = x0 + i * (tw + gap) + tw / 2, cy = h * 0.74f + tw * 9 / 32;
                r.set(cx - tw / 2 * k, cy - tw * 9 / 32 * k, cx + tw / 2 * k, cy + tw * 9 / 32 * k);
                p.setColor(i == cur ? 0xF2FFFFFF : 0x4DFFFFFF);
                c.drawRoundRect(r, rad, rad, p);
                if (i == cur && moving && !calm && ph > 90 && ph < 990) {
                    // the streak of light crossing the focused tile
                    float s = decel((ph - 90) / 900f, 1.4f), sx = r.left - r.width() * 1.1f + r.width() * 2.2f * s;
                    c.save();
                    clip.reset();
                    clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
                    c.clipPath(clip);
                    p.setShader(new android.graphics.LinearGradient(sx - r.width() * 0.3f, 0, sx + r.width() * 0.3f, 0,
                            new int[]{0x00000000, 0x55000000, 0x00000000}, null, android.graphics.Shader.TileMode.CLAMP));
                    c.drawRect(r, p);
                    p.setShader(null);
                    c.restore();
                }
            }
        }

        private float decel(float x, float f) { x = Math.max(0f, Math.min(1f, x)); return (float) (1 - Math.pow(1 - x, f)); }

        private float overshoot(float x, float tension) {
            x = Math.max(0f, Math.min(1f, x)) - 1f;
            return x * x * ((tension + 1) * x + tension) + 1f;
        }

        // ------------------------------------------------ screensaver preview

        private static final long SLIDE = 2600;

        private void saver(android.graphics.Canvas c) {
            int style = get(getContext(), SAVER);
            if (style == 5) {
                // Shuffle: step through the ones that have something to show
                java.util.List<Integer> pick = new java.util.ArrayList<>(java.util.Arrays.asList(1, 4));
                if (!posters.isEmpty()) pick.add(1, 2);
                if (!photos.isEmpty()) pick.add(pick.size() - 1, 3);
                style = pick.get((int) (t() / SLIDE % pick.size()));
            }
            float w = getWidth(), h = getHeight();
            if (style == 0) {
                p.setColor(0xF2050508);
                c.drawRect(0, 0, w, h, p);
                text(c, "Amazon's screensaver", w / 2, h / 2, h * 0.07f, 0x99FFFFFF, false, android.graphics.Paint.Align.CENTER);
            } else if (style == 1) {
                // the clock hops to a new spot (every minute on the TV; faster here)
                int spot = (int) (t() / SLIDE % 3);
                float x = w * (spot == 0 ? 0.08f : spot == 1 ? 0.42f : 0.2f), y = h * (spot == 0 ? 0.42f : spot == 1 ? 0.58f : 0.8f);
                float a = Math.min(1f, (t() % SLIDE) / 400f);
                text(c, clock(), x, y, h * 0.24f, alpha(0xFFFFFFFF, a), true, android.graphics.Paint.Align.LEFT);
                text(c, date(), x + w * 0.005f, y + h * 0.09f, h * 0.055f, alpha(0xB3FFFFFF, a), false, android.graphics.Paint.Align.LEFT);
            } else if (style == 2 || style == 3) {
                java.util.List<android.graphics.Bitmap> set = style == 2 ? posters : photos;
                if (set.isEmpty()) {
                    p.setColor(0xCC050508);
                    c.drawRect(0, 0, w, h, p);
                    text(c, loading ? "Loading…" : style == 2 ? "No posters yet" : "No photos in Pictures/tvhome",
                            w / 2, h / 2, h * 0.065f, 0x99FFFFFF, false, android.graphics.Paint.Align.CENTER);
                } else {
                    long t = t();
                    int i = (int) (t / SLIDE % set.size());
                    float fade = Math.min(1f, (t % SLIDE) / 700f);
                    if (t >= SLIDE && fade < 1f) cover(c, set.get((i + set.size() - 1) % set.size()), 255);
                    cover(c, set.get(i), (int) (255 * fade));
                    if (style == 2) {
                        p.setShader(new android.graphics.LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0xCC000000,
                                android.graphics.Shader.TileMode.CLAMP));
                        c.drawRect(0, 0, w, h, p);
                        p.setShader(null);
                        text(c, posterNames.get(i), w * 0.06f, h * 0.88f, h * 0.1f, alpha(0xFFFFFFFF, fade), true, android.graphics.Paint.Align.LEFT);
                    } else smallClock(c, w, h);
                }
            } else {
                smallClock(c, w, h); // Ambient: the sky behind, slowly moving, and a small clock
            }
        }

        private void smallClock(android.graphics.Canvas c, float w, float h) {
            text(c, clock(), w * 0.94f, h * 0.84f, h * 0.11f, 0xFFFFFFFF, true, android.graphics.Paint.Align.RIGHT);
            text(c, date(), w * 0.94f, h * 0.91f, h * 0.04f, 0xB3FFFFFF, false, android.graphics.Paint.Align.RIGHT);
        }

        /** Draw a bitmap filling the card (centre crop). */
        private void cover(android.graphics.Canvas c, android.graphics.Bitmap b, int a) {
            float w = getWidth(), h = getHeight(), s = Math.max(w / b.getWidth(), h / b.getHeight());
            float bw = b.getWidth() * s, bh = b.getHeight() * s;
            r.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
            img.setAlpha(a);
            c.drawBitmap(b, null, r, img);
        }

        private void text(android.graphics.Canvas c, String s, float x, float y, float size, int color, boolean serif,
                          android.graphics.Paint.Align align) {
            p.setColor(color);
            p.setTextSize(size);
            p.setTextAlign(align);
            p.setTypeface(serif ? Look.serif(getContext()) : Look.sans(getContext(), 500));
            p.setShadowLayer(size * 0.3f, 0, 0, 0x66000000);
            c.drawText(s, x, y, p);
            p.clearShadowLayer();
        }

        private String clock() { return android.text.format.DateFormat.format("h:mm", System.currentTimeMillis()).toString(); }
        private String date() { return android.text.format.DateFormat.format("EEEE d MMMM", System.currentTimeMillis()).toString(); }
        private int alpha(int color, float a) { return (color & 0xFFFFFF) | ((int) ((color >>> 24) * a) << 24); }

        /** Up to four posters and four photos, card-sized, once, off the main thread. */
        private void load() {
            if (loading || !posters.isEmpty() || !photos.isEmpty()) return;
            loading = true;
            int pw = Math.max(getWidth(), Look.dp(getContext(), 380));
            new Thread(() -> {
                java.util.List<android.graphics.Bitmap> ps = new java.util.ArrayList<>(), fs = new java.util.ArrayList<>();
                java.util.List<String> names = new java.util.ArrayList<>();
                try {
                    org.json.JSONArray t = Catalog.recs(getContext());
                    for (int i = 0; i < t.length() && ps.size() < 4; i++) {
                        android.graphics.Bitmap b = Net.image(getContext(), t.getJSONObject(i).optString("backdrop", null), pw);
                        if (b != null) { ps.add(b); names.add(t.getJSONObject(i).optString("title")); }
                    }
                } catch (Exception ignored) { }
                java.io.File[] files = Screensaver.PHOTOS.listFiles((d, n) -> n.matches("(?i).+\\.(jpe?g|png|webp)"));
                if (files != null) {
                    java.util.List<java.io.File> list = new java.util.ArrayList<>(java.util.Arrays.asList(files));
                    java.util.Collections.shuffle(list);
                    for (java.io.File f : list) {
                        if (fs.size() >= 4) break;
                        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                        o.inSampleSize = 1;
                        while (o.outWidth / (o.inSampleSize * 2) >= pw) o.inSampleSize *= 2;
                        o.inJustDecodeBounds = false;
                        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                        if (b != null) fs.add(b);
                    }
                }
                post(() -> {
                    loading = false;
                    if (!isAttachedToWindow()) { for (android.graphics.Bitmap b : ps) b.recycle(); for (android.graphics.Bitmap b : fs) b.recycle(); return; }
                    posters.addAll(ps); posterNames.addAll(names); photos.addAll(fs);
                    restart();
                });
            }, "preview").start();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            for (android.graphics.Bitmap b : posters) b.recycle();
            for (android.graphics.Bitmap b : photos) b.recycle();
            posters.clear(); photos.clear(); posterNames.clear();
        }
    }

    /** On/off switch; dark on the white highlight, white elsewhere. */
    private static final class Toggle extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private boolean on, ink;
        private float pos;
        Toggle(Context c) { super(c); }
        void set(boolean v) {
            on = v;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(pos, v ? 1f : 0f);
            a.setDuration(Look.ms(getContext(), 220));
            a.addUpdateListener(x -> { pos = (float) x.getAnimatedValue(); invalidate(); });
            a.start();
        }
        void setInk(boolean dark) { ink = dark; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), k = h - 6;
            int onTrack = ink ? 0xFF16161C : 0xF2FFFFFF, offTrack = ink ? 0x3316161C : 0x40FFFFFF;
            p.setColor(blend(offTrack, onTrack, pos));
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            // Dark knob on a light track, white knob on a dark one.
            p.setColor(on != ink ? 0xFF16161C : 0xFFFFFFFF);
            c.drawCircle(3 + k / 2 + (w - k - 6) * pos, h / 2, k / 2, p);
        }
        private static int blend(int a, int b, float t) {
            return Color.argb((int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                    (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                    (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                    (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }
    }

    /** One dot per option, the current one bright. */
    private static final class Dots extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int n;
        private int cur;
        private boolean ink;
        Dots(Context c, int n) { super(c); this.n = n; }
        void set(int i) { cur = i; invalidate(); }
        void setInk(boolean dark) { ink = dark; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float d = getHeight();
            int base = ink ? 0x0016161C : 0x00FFFFFF;
            for (int i = 0; i < n; i++) {
                p.setColor(base | ((i == cur ? 0xF2 : 0x4D) << 24));
                c.drawCircle(d / 2 + i * d * 1.8f, d / 2, d / 2 * (i == cur ? 1f : 0.75f), p);
            }
        }
    }
}
