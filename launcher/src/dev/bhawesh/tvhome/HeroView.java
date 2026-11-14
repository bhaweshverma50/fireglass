package dev.bhawesh.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Full-screen backdrop of the featured title. Each image is baked off the main thread, with its
 * scrims, into one half-resolution bitmap ({@link #bake}), so a frame here is a single
 * full-screen draw; a crossfade (with a small settling zoom) is two, and only while it runs.
 * Held as a HARDWARE bitmap: as a regular one this GPU re-sent it every frame (fast sweeps along
 * the apps row went from 96% to 0.3% dropped frames). A continuous Ken Burns drift was tried
 * too: it redrew the screen 20 times a second and made focus moves stutter.
 */
final class HeroView extends View {
    private static final long FADE_MS = 700;
    private Bitmap cur, prev;
    private long curAt;
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    // Home's picture: this fraction of the screen width, on the right, starting PANEL_TOP of the
    // height down (clear of the top bar) and reaching to just above the apps row.
    static final float PANEL = 0.66f, PANEL_TOP = 0.08f;

    HeroView(Context c) { super(c); }

    /** Show a bitmap made by {@link #bake}; null clears (the sky shows instead). */
    void setImage(Bitmap b) {
        if (b == cur) return;
        // GPU-side bitmaps are only freed when the GC finalises them; free ours as soon as
        // they're off screen so paging through titles doesn't pile up texture memory.
        if (prev != null && prev != cur) prev.recycle();
        prev = cur;
        cur = b;
        curAt = SystemClock.uptimeMillis();
        invalidate();
    }

    boolean hasImage() { return cur != null; }

    /** Free both bitmaps now (screensaver ending). */
    void release() {
        if (prev != null && prev != cur) prev.recycle();
        if (cur != null) cur.recycle();
        prev = cur = null;
    }

    @Override protected void onDraw(Canvas c) {
        if (cur == null) return;
        float t = Math.min(1f, (SystemClock.uptimeMillis() - curAt) / (float) FADE_MS);
        float e = t * t * (3 - 2 * t);
        if (panel > 0) {
            // Soft-edged panels: a true crossfade (old out while new comes in) and no zoom. Keeping
            // the old one opaque underneath left it showing through the new one's faded edge,
            // then vanishing all at once when the fade ended.
            if (t < 1f && prev != null) draw(c, prev, 1f, Math.round(255 * (1 - e)));
            draw(c, cur, 1f, Math.round(255 * e));
        } else {
            if (t < 1f && prev != null) draw(c, prev, 1f, 255);
            draw(c, cur, 1.03f - 0.03f * e, Math.round(255 * e));
        }
        if (t < 1f) postInvalidateOnAnimation();
        else if (prev != null) { prev.recycle(); prev = null; }
    }

    private float panel; // 0: cover the view; else the picture's width as a fraction, top right

    /** Draw images as a top-right panel {@code fraction} of the width wide (see {@link #bakeRight}). */
    void setPanel(float fraction) { panel = fraction; }

    private void draw(Canvas c, Bitmap b, float zoom, int alpha) {
        if (panel > 0) {
            // Only the panel's pixels are drawn: blending a mostly transparent full-screen image
            // over the sky cost a full extra pass a frame and brought the stutter back.
            float s = getWidth() * panel / b.getWidth() * zoom;
            m.setScale(s, s);
            m.postTranslate(getWidth() - b.getWidth() * s, getHeight() * PANEL_TOP);
            img.setAlpha(alpha);
            c.drawBitmap(b, m, img);
            return;
        }
        float s = Math.max(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight()) * zoom;
        m.setScale(s, s);
        // Zoom about the right side, where the picture is; the dark left (text) stays put.
        m.postTranslate(getWidth() - b.getWidth() * s, (getHeight() - b.getHeight() * s) / 2f);
        img.setAlpha(alpha);
        c.drawBitmap(b, m, img);
    }

    /**
     * Background-thread work: the picture cropped to 16:9 (anchored right of centre) with the
     * scrims painted in: near-black on the left third where the text sits, darkening toward the
     * apps row and the top bar, and a touch darker overall. {@code w}x{@code h} is half screen.
     */
    /** A photo for the screensaver: cropped to fill, only the corner under the clock darkened. */
    static Bitmap bakePhoto(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix mm = new Matrix();
        mm.setScale(s, s);
        mm.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        c.drawBitmap(src, mm, new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new android.graphics.RadialGradient(w, h, w * 0.45f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return Look.gpu(out);
    }

    /**
     * Home's featured picture: a 16:9 panel in the top right at its own proportions (not stretched
     * across the screen), melting into transparency on its left and bottom edges so the sky shows
     * through behind the text. Panel-sized (draw with {@link #setPanel}); HARDWARE like {@link #bake}.
     */
    static Bitmap bakeRight(Bitmap src, int screenW) {
        float rw = Math.round(screenW * PANEL), rh = Math.round(rw * 9 / 16), x = 0, w = rw;
        Bitmap out = Bitmap.createBitmap((int) rw, (int) rh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = Math.max(rw / src.getWidth(), rh / src.getHeight());
        Matrix mm = new Matrix();
        mm.setScale(s, s);
        mm.postTranslate(x + (rw - src.getWidth() * s) / 2f, (rh - src.getHeight() * s) / 2f);
        c.save();
        c.clipRect(x, 0, w, rh);
        c.drawBitmap(src, mm, new Paint(Paint.FILTER_BITMAP_FLAG));
        c.drawColor(0x1A000000);
        c.restore();
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        // Eased fades (a straight ramp leaves a visible line where it starts): long on the left,
        // where the text is, and on the top and bottom, so no edge of the picture shows.
        p.setShader(new LinearGradient(x, 0, x + rw * 0.62f, 0, ease(false), easeStops(), Shader.TileMode.CLAMP));
        c.drawRect(x, 0, w, rh, p);
        p.setShader(new LinearGradient(0, 0, 0, rh * 0.34f, ease(false), easeStops(), Shader.TileMode.CLAMP));
        c.drawRect(x, 0, w, rh, p);
        p.setShader(new LinearGradient(0, rh * 0.5f, 0, rh, ease(true), easeStops(), Shader.TileMode.CLAMP));
        c.drawRect(x, 0, w, rh, p);
        return Look.gpu(out);
    }

    private static final int EASE_STEPS = 9;

    /** Mask alphas along a smoothstep from clear to opaque (or back, if {@code out}). */
    private static int[] ease(boolean out) {
        int[] c = new int[EASE_STEPS];
        for (int i = 0; i < EASE_STEPS; i++) {
            float t = i / (float) (EASE_STEPS - 1), e = t * t * (3 - 2 * t);
            c[i] = Math.round(255 * (out ? 1 - e : e)) << 24;
        }
        return c;
    }

    private static float[] easeStops() {
        float[] f = new float[EASE_STEPS];
        for (int i = 0; i < EASE_STEPS; i++) f[i] = i / (float) (EASE_STEPS - 1);
        return f;
    }

    static Bitmap bake(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF05060A);
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight()) * 1.04f;
        Matrix mm = new Matrix();
        mm.setScale(s, s);
        float over = src.getWidth() * s - w;
        mm.postTranslate(-over * 0.85f, (h - src.getHeight() * s) * 0.35f);
        c.drawBitmap(src, mm, new Paint(Paint.FILTER_BITMAP_FLAG));
        c.drawColor(0x33000000);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xFA05060A, 0xF005060A, 0xC705060A, 0x4D05060A, 0x00000000},
                new float[]{0f, 0.32f, 0.54f, 0.74f, 0.9f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h * 0.38f, 0, h, new int[]{0x00000000, 0xB305060A, 0xFC05060A},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.3f, 0xCC05060A, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // Hardware bitmap: uploaded to the GPU once, here, never again while drawing.
        return Look.gpu(out);
    }
}
