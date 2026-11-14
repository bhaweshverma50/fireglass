package dev.bhawesh.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The backdrop: a sky whose colours follow the real time of day (night, dawn, day, dusk), with
 * soft blobs of light drifting slowly, the focused app's colour rising from behind the shelf, and
 * a fixed film grain on top. Rendered on the CPU into a 128x72 bitmap and drawn scaled up with
 * bilinear filtering, which is both the blur and the cheap part; ~15 fps since nothing moves fast.
 * Positions come from the wall clock, so any two SkyViews (home, loading screen) show the same frame.
 * "Wallpaper" shows the picture chosen on the phone instead ({@link Wallpapers}), drawn once, still.
 */
final class SkyView extends View {
    private static final int W = 128, H = 72;

    //                                   base        blob 1      blob 2      blob 3
    private static final int[] NIGHT = {0xFF070B1F, 0xFF2E3A8C, 0xFF6E3FB8, 0xFF1B6F8A};
    private static final int[] DAWN = {0xFF2A2350, 0xFFF6B49A, 0xFFC9A3E8, 0xFF5B6CC9};
    private static final int[] DAY = {0xFF0E2A5A, 0xFF3E7BFA, 0xFF3FB8C9, 0xFF5CC9A0};
    private static final int[] DUSK = {0xFF2B1240, 0xFFFF8A5B, 0xFFE2508F, 0xFF6A3FD1};
    // Fixed looks for the home's "Background" setting; null palette = follow the time of day.
    static final String[] LOOKS = {"Live sky", "Aurora", "Dusk", "Ocean", "Graphite", "Wallpaper"};
    static final int WALLPAPER = 5;
    private static final int[][] LOOK_PALETTES = {
            null,
            {0xFF06141A, 0xFF1FA38A, 0xFF3B4FD8, 0xFF7A3FC4},
            {0xFF1E0E26, 0xFFE8774F, 0xFFC2457E, 0xFF5B3BB8},
            {0xFF04122A, 0xFF1D6FD1, 0xFF17A7C2, 0xFF2D3FA8},
            {0xFF0B0C10, 0xFF3A3F4B, 0xFF262A33, 0xFF4B5160},
    };
    private int[] fixed;
    private boolean wallpaper;

    /** Which of {@link #LOOKS}. Wallpaper with no picture set shows the live sky. */
    void setLook(int i) {
        wallpaper = i == WALLPAPER;
        fixed = wallpaper ? null : LOOK_PALETTES[Math.max(0, Math.min(i, LOOK_PALETTES.length - 1))];
        invalidate();
    }

    // Every SkyView on screen, so a background chosen on the phone shows at once.
    private static final Set<SkyView> live = Collections.newSetFromMap(new WeakHashMap<>());

    static void refreshAll(int look) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (SkyView v : new ArrayList<>(live)) v.setLook(look);
        });
    }

    private static final float[] HOURS = {0f, 5.5f, 8f, 11f, 16f, 18.5f, 20.5f, 24f};
    private static final int[][] KEYS = {NIGHT, NIGHT, DAWN, DAY, DAY, DUSK, NIGHT, NIGHT};

    // Per blob: x/y drift speeds (rad/s), phases, reach, strength. Slow and out of step.
    private static final double[][] BLOBS = {
            {0.031, 0.023, 0.0, 1.7, 0.55, 0.80},
            {0.019, 0.029, 2.1, 0.4, 0.50, 0.70},
            {0.026, 0.017, 4.2, 3.3, 0.45, 0.65},
    };

    private final Bitmap sky = opaque(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));

    /** Opaque, so drawing it needs no blending with what's under it. */
    private static Bitmap opaque(Bitmap b) { b.setHasAlpha(false); return b; }
    private final int[] px = new int[W * H];
    private final float[] pal = new float[12];
    private final float[] bx = new float[4], by = new float[4], reach = new float[4], str = new float[4];
    private final Paint skyPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint grain = new Paint();
    private final Rect dst = new Rect();
    private static final boolean GRAIN = !Look.LITE;
    private boolean running, grainOn = GRAIN;

    /** The grain is a full-screen blend each frame; skipped while the home shows a picture. */
    void setGrain(boolean on) { on &= GRAIN; if (grainOn != on) { grainOn = on; invalidate(); } }

    // Accent: the focused app's colour and how strongly it shows, eased over ACCENT_MS.
    private static final long ACCENT_MS = 700;
    private int accentFrom = 0xFF3A3A3A, accentTo = 0xFF3A3A3A;
    private float levelFrom, levelTo;
    private long accentAt;
    // While the remote is in use the sky holds still, so focus animations get the whole GPU.
    private static final long HOLD_MS = 450;
    private long holdUntil;

    SkyView(Context c) {
        super(c);
        grain.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        if (run) invalidate();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); live.add(this); setRunning(true); }
    @Override protected void onDetachedFromWindow() { setRunning(false); live.remove(this); super.onDetachedFromWindow(); }

    /** Ease the accent to {@code color} at {@code level} (0..1). */
    /** Remote in use: pause the drift for a moment. */
    void hold() {
        holdUntil = SystemClock.uptimeMillis() + HOLD_MS;
    }

    void setAccent(int color, float level, boolean animate) {
        long now = SystemClock.uptimeMillis();
        float t = progress(now);
        accentFrom = mix(accentFrom, accentTo, t);
        levelFrom = levelFrom + (levelTo - levelFrom) * t;
        accentTo = color;
        levelTo = level;
        // The light catches up once the remote goes quiet.
        accentAt = animate ? Math.max(now, holdUntil) : 0;
        if (!animate) { accentFrom = color; levelFrom = level; }
        invalidate();
    }

    private float progress(long now) {
        if (accentAt == 0) return 1f;
        float t = Math.max(0f, Math.min(1f, (now - accentAt) / (float) ACCENT_MS));
        return t * t * (3 - 2 * t);
    }

    @Override protected void onDraw(Canvas c) {
        Bitmap wall = wallpaper ? Wallpapers.backdrop(getContext(), this::invalidate) : null;
        if (wall != null) {
            // A still picture: one draw, no redraw loop.
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(wall, null, dst, skyPaint);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now >= holdUntil) render(now);
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(sky, null, dst, skyPaint);
        if (grainOn) c.drawPaint(grain);
        if (!running) return;
        if (now < holdUntil) postInvalidateDelayed(holdUntil - now);
        else postInvalidateDelayed(Math.abs(now - accentAt) < ACCENT_MS ? 33 : 66);
    }

    private void render(long now) {
        palette();
        float t = progress(now);
        int accent = mix(accentFrom, accentTo, t);
        float level = levelFrom + (levelTo - levelFrom) * t;
        double sec = System.currentTimeMillis() / 1000.0;

        for (int i = 0; i < 3; i++) {
            double[] b = BLOBS[i];
            bx[i] = (float) (0.5 + 0.42 * Math.sin(sec * b[0] + b[2]));
            by[i] = (float) (0.42 + 0.34 * Math.sin(sec * b[1] + b[3]));
            reach[i] = (float) b[4];
            str[i] = (float) b[5];
        }
        bx[3] = (float) (0.5 + 0.12 * Math.sin(sec * 0.05));
        by[3] = 1.05f;
        reach[3] = 0.62f;
        str[3] = level;
        float ar = Color.red(accent), ag = Color.green(accent), ab = Color.blue(accent);

        for (int y = 0; y < H; y++) {
            float fy = y / (float) (H - 1);
            // Darker toward the top and bottom edges, where the clock and the shelf sit.
            float shade = 0.70f + 0.30f * (float) Math.sin(Math.PI * (0.15f + 0.8f * fy));
            for (int x = 0; x < W; x++) {
                float fx = x / (float) (W - 1);
                float r = pal[0], g = pal[1], b = pal[2];
                for (int i = 0; i < 4; i++) {
                    float dx = (fx - bx[i]) * 1.78f, dy = fy - by[i];
                    float d = 1f - (dx * dx + dy * dy) / (reach[i] * reach[i]);
                    if (d <= 0f) continue;
                    float w = d * d * str[i];
                    float cr, cg, cb;
                    if (i < 3) { cr = pal[3 + i * 3]; cg = pal[4 + i * 3]; cb = pal[5 + i * 3]; }
                    else { cr = ar; cg = ag; cb = ab; }
                    r += (cr - r) * w;
                    g += (cg - g) * w;
                    b += (cb - b) * w;
                }
                px[y * W + x] = 0xFF000000 | ((int) (r * shade) << 16) | ((int) (g * shade) << 8) | (int) (b * shade);
            }
        }
        sky.setPixels(px, 0, W, 0, 0, W, H);
    }

    /** Palette for the current time of day, blended between neighbouring keys. */
    private void palette() {
        if (fixed != null) {
            for (int i = 0; i < 4; i++) {
                pal[i * 3] = Color.red(fixed[i]);
                pal[i * 3 + 1] = Color.green(fixed[i]);
                pal[i * 3 + 2] = Color.blue(fixed[i]);
            }
            return;
        }
        Calendar now = Calendar.getInstance();
        float h = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f;
        int k = 0;
        while (k < HOURS.length - 2 && h >= HOURS[k + 1]) k++;
        float t = (h - HOURS[k]) / (HOURS[k + 1] - HOURS[k]);
        t = t * t * (3 - 2 * t);
        for (int i = 0; i < 4; i++) {
            int c = mix(KEYS[k][i], KEYS[k + 1][i], t);
            pal[i * 3] = Color.red(c);
            pal[i * 3 + 1] = Color.green(c);
            pal[i * 3 + 2] = Color.blue(c);
        }
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Fixed grain: faint light and dark specks. Also hides banding in the dark gradients. */
    private static Bitmap noise() {
        int n = 128;
        int[] p = new int[n * n];
        Random rnd = new Random(7);
        for (int i = 0; i < p.length; i++) {
            int a = rnd.nextInt(14);
            p[i] = (a << 24) | (rnd.nextBoolean() ? 0xFFFFFF : 0x000000);
        }
        return Bitmap.createBitmap(p, n, n, Bitmap.Config.ARGB_8888);
    }
}
