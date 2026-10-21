package dev.bhawesh.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import java.util.Locale;

/**
 * Shared look of the home screen and of the loading screen HomeService shows while Android holds
 * the Home bounce: fonts, glass, icons, and the header and shelf layout both screens must agree on.
 */
final class Look {
    private Look() { }

    // Layout, in dp. The loading screen and the home screen must line up exactly.
    static final int PAD_X = 56, PAD_TOP = 36, PAD_BOTTOM = 30, TILE_GAP = 18;
    static final int TILE_RADIUS = 12, APP_W = 168, HERO_TOP = 140, ROW_LIFT = 18;

    // ---------------------------------------------------------------- type

    private static Typeface serif;
    private static final SparseArray<Typeface> sans = new SparseArray<>();

    /** Instrument Serif: the clock and the focused app's name. */
    static Typeface serif(Context c) {
        if (serif == null) serif = Typeface.createFromAsset(c.getAssets(), "fonts/InstrumentSerif-Regular.ttf");
        return serif;
    }

    /** Figtree (variable) at a weight: everything else. */
    static Typeface sans(Context c, int weight) {
        Typeface t = sans.get(weight);
        if (t == null) {
            t = new Typeface.Builder(c.getAssets(), "fonts/Figtree.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
            if (t == null) t = Typeface.DEFAULT;
            sans.put(weight, t);
        }
        return t;
    }

    static TextView text(Context c, Typeface face, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ---------------------------------------------------------------- motion

    private static float animScale = -1;

    /**
     * Designed duration in ms, undoing the system animator scale (set to 0.5x on this TV to speed
     * up Fire OS menus). Animations switched off stay off.
     */
    static long ms(Context c, long designed) {
        if (animScale < 0) animScale = Settings.Global.getFloat(c.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return animScale <= 0 ? 0 : (long) (designed / animScale);
    }

    // ---------------------------------------------------------------- shared layout

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Top left: the time large, and beside it a greeting over the date and weather. */
    static final class TopBar {
        final LinearLayout view;
        final TextView clock, ampm, greeting, date, weather;

        TopBar(Context c) {
            view = new LinearLayout(c);
            view.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout time = new LinearLayout(c);
            time.setGravity(Gravity.BOTTOM);
            clock = text(c, serif(c), 44, Color.WHITE);
            ampm = text(c, sans(c, 500), 14, 0xB3FFFFFF);
            time.addView(clock);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
            ap.setMargins(dp(c, 5), 0, 0, dp(c, 8));
            time.addView(ampm, ap);
            view.addView(time);

            LinearLayout side = new LinearLayout(c);
            side.setOrientation(LinearLayout.VERTICAL);
            greeting = text(c, sans(c, 600), 15, 0xF2FFFFFF);
            LinearLayout line = new LinearLayout(c);
            date = text(c, sans(c, 500), 13, 0x99FFFFFF);
            weather = text(c, sans(c, 500), 13, 0x99FFFFFF);
            line.addView(date);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
            wp.setMargins(dp(c, 16), 0, 0, 0);
            line.addView(weather, wp);
            side.addView(greeting);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, dp(c, 2), 0, 0);
            side.addView(line, lp);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.setMargins(dp(c, 18), 0, 0, 0);
            view.addView(side, sp);
            for (TextView t : new TextView[]{clock, ampm, greeting, date, weather}) t.setShadowLayer(dp(c, 12), 0, dp(c, 1), 0x66000000);
            update(c);
        }

        /** Over a photo: brighter secondary lines and a tighter, darker shadow so each letter holds its edge. */
        void onPhoto(Context c, boolean photo) {
            date.setTextColor(photo ? 0xE6FFFFFF : 0x99FFFFFF);
            weather.setTextColor(photo ? 0xE6FFFFFF : 0x99FFFFFF);
            ampm.setTextColor(photo ? 0xE6FFFFFF : 0xB3FFFFFF);
            for (TextView t : new TextView[]{clock, ampm, greeting, date, weather}) {
                t.setShadowLayer(dp(c, photo ? 5 : 12), 0, dp(c, 1), photo ? 0xD9000000 : 0x66000000);
            }
        }

        void update(Context c) {
            Calendar now = Calendar.getInstance();
            int h = now.get(Calendar.HOUR_OF_DAY);
            boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
            clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
            ampm.setText(is24 ? "" : h < 12 ? "am" : "pm");
            greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening");
            date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.getTime()));
        }

        FrameLayout.LayoutParams lp(Context c) {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            p.setMargins(dp(c, PAD_X), dp(c, PAD_TOP - 4), 0, 0);
            return p;
        }
    }

    /** The apps row along the bottom of the first screen. */
    static FrameLayout.LayoutParams appsLp(Context c) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        p.setMargins(0, 0, 0, dp(c, PAD_BOTTOM - ROW_LIFT));
        return p;
    }

    static LinearLayout.LayoutParams tileLp(Context c, int widthDp, boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(c, widthDp), -2);
        p.setMargins(first ? 0 : dp(c, TILE_GAP), 0, 0, 0);
        return p;
    }

    static void roundClip(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    // ---------------------------------------------------------------- loading screen

    /**
     * What HomeService shows over the Amazon home while Android holds the bounce back (~5 s):
     * the same sky and top bar, the hero text and the apps row as glass placeholders, with light
     * running across them. Swallows keys so nothing reaches the Amazon home underneath.
     */
    static View loading(Context c, int tiles, int accent) {
        FrameLayout root = new FrameLayout(c) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) { return true; }
        };
        root.setFocusable(true);
        root.setBackgroundColor(Color.BLACK);
        SkyView sky = new SkyView(c);
        sky.setLook(HomeSettings.get(c, HomeSettings.LOOK));
        sky.setAccent(accent, 0.55f, false);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        boolean minimal = HomeSettings.get(c, HomeSettings.STYLE) == HomeSettings.STYLE_MINIMAL;
        TopBar bar = new TopBar(c);
        bar.weather.setText(c.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("line", ""));
        root.addView(bar.view, bar.lp(c));

        List<Glass> glasses = new ArrayList<>();
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        int[][] bars = minimal ? new int[0][] : new int[][]{{150, 12}, {340, 40}, {220, 14}, {440, 14}, {380, 14}, {190, 38}};
        for (int i = 0; i < bars.length; i++) {
            View b = new View(c);
            Glass g = new Glass(dp(c, bars[i][1] / 2f), 0.8f);
            glasses.add(g);
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, bars[i][0]), dp(c, bars[i][1]));
            lp.setMargins(0, i == 0 ? 0 : dp(c, i == 5 ? 22 : 12), 0, 0);
            info.addView(b, lp);
        }
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ip.setMargins(dp(c, PAD_X), dp(c, HERO_TOP + 8), 0, 0);
        root.addView(info, ip);

        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(c, PAD_X), dp(c, ROW_LIFT), dp(c, PAD_X), dp(c, ROW_LIFT));
        for (int i = 0; i < tiles; i++) {
            View ghost = new MainActivity.AspectFrame(c);
            Glass g = new Glass(dp(c, TILE_RADIUS), 0.8f);
            glasses.add(g);
            ghost.setBackground(g);
            row.addView(ghost, tileLp(c, APP_W, i == 0));
        }
        root.addView(row, appsLp(c));

        ValueAnimator run = ValueAnimator.ofFloat(0f, 1f);
        run.setDuration(ms(c, 1500));
        run.setRepeatCount(ValueAnimator.INFINITE);
        run.setInterpolator(new LinearInterpolator());
        run.addUpdateListener(a -> { for (Glass g : glasses) g.setSweep((float) a.getAnimatedValue()); });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { run.start(); }
            @Override public void onViewDetachedFromWindow(View v) { run.cancel(); }
        });
        return root;
    }

    // ---------------------------------------------------------------- drawables

    /**
     * Frosted glass for a soft backdrop: a translucent white body with a top-lit sheen and a
     * hairline rim that is bright along the top edge and fades down the sides.
     * ponytail: no real blur (RenderEffect is Android 12, this is 9); over the blurred sky it reads the same.
     */
    static final class Glass extends Drawable {
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius, strength;
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();
        private float sweep = -1f; // 0..1 while a band of light runs across; -1 for none
        private final Paint smoke = new Paint(Paint.ANTI_ALIAS_FLAG); // dark base, for glass over a photo

        Glass(float radius, float strength) {
            this.radius = radius;
            this.strength = strength;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f);
            smoke.setColor(Color.TRANSPARENT); // a Paint starts opaque black: no smoke unless asked for
        }

        @Override protected void onBoundsChange(Rect b) {
            r.set(b);
            r.inset(0.75f, 0.75f);
            body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{white(0.17f * strength), white(0.07f * strength), white(0.10f * strength)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{white(0.50f), white(0.08f), white(0.16f)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            clip.reset();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
        }

        /** A dark base under the frost (0 none, 1 near opaque): white frost alone adds no contrast over a bright photo. */
        void setSmoke(float f) {
            smoke.setColor(Color.argb(Math.round(0xE6 * f), 0x0C, 0x0C, 0x10));
            invalidateSelf();
        }

        void setSweep(float f) {
            sweep = f;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            if (smoke.getAlpha() > 0) c.drawRoundRect(r, radius, radius, smoke);
            c.drawRoundRect(r, radius, radius, body);
            if (sweep >= 0f) {
                float w = r.width() * 0.3f, x = r.left - w + (r.width() + w) * sweep;
                band.setShader(new LinearGradient(x, 0, x + w, 0,
                        new int[]{white(0f), white(0.16f), white(0f)}, null, Shader.TileMode.CLAMP));
                c.save();
                c.clipPath(clip);
                c.drawRect(x, r.top, x + w, r.bottom, band);
                c.restore();
            }
            c.drawRoundRect(r, radius, radius, rim);
        }

        @Override public void setAlpha(int a) { body.setAlpha(a); rim.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Line icons drawn in code (no emoji glyphs): Wi-Fi and a gear. */
    static final class Icon extends Drawable {
        static final int WIFI = 0, GEAR = 1, PLAY = 2, RECENTS = 3, SEARCH = 4, BULB = 5;
        private final int kind, size;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        boolean dim; // Wi-Fi offline

        Icon(int kind, int size) {
            this.kind = kind;
            this.size = size;
            p.setStrokeCap(Paint.Cap.ROUND);
            setBounds(0, 0, size, size);
        }

        void setColor(int color) { p.setColor(color); invalidateSelf(); }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = b.width(), cx = b.exactCenterX(), cy = b.exactCenterY();
            if (kind == RECENTS) {
                // Two cards, one behind the other.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.1f);
                float r = s * 0.12f;
                arc.set(b.left + s * 0.28f, b.top + s * 0.1f, b.right - s * 0.06f, b.top + s * 0.62f);
                c.drawRoundRect(arc, r, r, p);
                p.setStyle(Paint.Style.FILL);
                arc.set(b.left + s * 0.06f, b.top + s * 0.34f, b.right - s * 0.28f, b.bottom - s * 0.1f);
                c.drawRoundRect(arc, r, r, p);
                return;
            }
            if (kind == BULB) {
                // A bulb: round glass narrowing to a neck, two lines for the base.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.1f);
                float r = s * 0.27f, by = b.top + s * 0.36f;
                arc.set(cx - r, by - r, cx + r, by + r);
                c.drawArc(arc, 140, 260, false, p);
                float nx = s * 0.13f, ny = b.top + s * 0.68f;
                c.drawLine(cx - r * 0.77f, by + r * 0.64f, cx - nx, ny, p);
                c.drawLine(cx + r * 0.77f, by + r * 0.64f, cx + nx, ny, p);
                c.drawLine(cx - nx, ny, cx + nx, ny, p);
                c.drawLine(cx - nx * 0.8f, b.top + s * 0.82f, cx + nx * 0.8f, b.top + s * 0.82f, p);
                return;
            }
            if (kind == SEARCH) {
                // A lens and its handle.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.12f);
                float r = s * 0.29f, lx = b.left + s * 0.42f, ly = b.top + s * 0.42f;
                c.drawCircle(lx, ly, r, p);
                float h = r * 0.7071f;
                c.drawLine(lx + h, ly + h, b.right - s * 0.1f, b.bottom - s * 0.1f, p);
                return;
            }
            if (kind == PLAY) {
                p.setStyle(Paint.Style.FILL);
                android.graphics.Path t = new android.graphics.Path();
                t.moveTo(b.left + s * 0.24f, b.top + s * 0.14f);
                t.lineTo(b.left + s * 0.86f, cy);
                t.lineTo(b.left + s * 0.24f, b.bottom - s * 0.14f);
                t.close();
                c.drawPath(t, p);
                return;
            }
            if (kind == WIFI) {
                float ox = cx, oy = b.top + s * 0.84f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.1f);
                int full = p.getAlpha();
                for (int i = 1; i <= 3; i++) {
                    float rr = s * (0.22f + 0.2f * (i - 1));
                    arc.set(ox - rr, oy - rr, ox + rr, oy + rr);
                    if (dim && i > 1) p.setAlpha(full / 3);
                    c.drawArc(arc, 225, 90, false, p);
                    p.setAlpha(full);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(ox, oy - s * 0.02f, s * 0.07f, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                float tw = s * 0.15f;
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, cx, cy);
                    c.drawRoundRect(cx - tw / 2, cy - s * 0.46f, cx + tw / 2, cy - s * 0.26f, tw / 3, tw / 3, p);
                    c.restore();
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.13f);
                c.drawCircle(cx, cy, s * 0.26f, p);
            }
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Average a banner's vivid pixels, then push saturation so a glow reads as a colour. */
    static int sampleColor(Drawable d) {
        int color = 0xFF3A3A3A;
        try {
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(24, 14, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 24, 14);
            d.draw(new Canvas(bm));
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        return color;
    }

    static int white(float a) {
        return Color.argb(Math.round(Math.max(0f, Math.min(1f, a)) * 255), 255, 255, 255);
    }
}
