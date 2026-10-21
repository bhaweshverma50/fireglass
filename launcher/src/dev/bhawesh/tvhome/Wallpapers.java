package dev.bhawesh.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Pictures the phone sends: kept in {@link Screensaver#PHOTOS} (so the Photos screensaver shows
 * them), each cropped to 16:9 and stored as a 1920x1080 JPEG whatever the phone sent. One of them
 * can be the home's background ("Wallpaper" in {@link SkyView#LOOKS}), held once as a GPU bitmap.
 */
final class Wallpapers {
    static final File DIR = Screensaver.PHOTOS;
    static final String KEY = "wallpaper"; // HomeSettings pref: file name of the background
    private static final int W = 1920, H = 1080;
    private static final String NAME = "[A-Za-z0-9._-]{1,80}\\.(?i:jpe?g|png|webp)";

    /** The picture called {@code name} in {@link #DIR}, or null for a bad or missing name. */
    static File file(String name) {
        if (name == null || !name.matches(NAME)) return null;
        File f = new File(DIR, name);
        return f.isFile() ? f : null;
    }

    /**
     * Read {@code length} bytes of an image and store it; returns its name, or null if it isn't
     * an image. Written to a temp file first, so a large photo never sits whole in memory.
     */
    // ponytail: one upload at a time (a full-size decode each); the phone sends them in turn.
    static synchronized String save(Context c, InputStream in, int length) throws IOException {
        File tmp = new File(c.getCacheDir(), "upload.tmp");
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int left = length; left > 0; ) {
                    int n = in.read(buf, 0, Math.min(buf.length, left));
                    if (n < 0) throw new IOException("short upload");
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
            Bitmap src = decode(tmp, W, H);
            if (src == null) return null;
            Bitmap pic = fill(src, W, H);
            src.recycle();
            if (!DIR.isDirectory() && !DIR.mkdirs()) throw new IOException("no " + DIR);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File f = new File(DIR, "up-" + stamp + ".jpg");
            for (int i = 2; f.exists(); i++) f = new File(DIR, "up-" + stamp + "-" + i + ".jpg");
            try (OutputStream out = new FileOutputStream(f)) {
                pic.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }
            pic.recycle();
            return f.getName();
        } finally {
            tmp.delete();
        }
    }

    /** Every picture, newest first, and which one is the background. */
    static JSONObject list(Context c) throws JSONException {
        File[] files = DIR.listFiles((d, n) -> n.matches(NAME));
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        JSONArray items = new JSONArray();
        for (File f : files) {
            items.put(new JSONObject().put("name", f.getName()).put("bytes", f.length())
                    .put("modified", f.lastModified()).put("uploaded", f.getName().startsWith("up-")));
        }
        String bg = background(c);
        return new JSONObject().put("items", items).put("background", bg == null ? JSONObject.NULL : bg)
                .put("look", HomeSettings.get(c, HomeSettings.LOOK));
    }

    /** A 320x180 JPEG of the picture, for the phone's grid. */
    static byte[] thumb(String name) {
        File f = file(name);
        if (f == null) return null;
        Bitmap src = decode(f, 320, 180);
        if (src == null) return null;
        Bitmap t = fill(src, 320, 180);
        src.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.compress(Bitmap.CompressFormat.JPEG, 75, out);
        t.recycle();
        return out.toByteArray();
    }

    static boolean delete(Context c, String name) {
        File f = file(name);
        if (f == null || !f.delete()) return false;
        if (name.equals(HomeSettings.prefs(c).getString(KEY, null))) setBackground(c, null);
        return true;
    }

    /** The background picture's name, if it's set and still there. */
    static String background(Context c) {
        String n = HomeSettings.prefs(c).getString(KEY, null);
        return file(n) == null ? null : n;
    }

    /** Make {@code name} the home's background (Background: Wallpaper), or none (back to Live sky). */
    static void setBackground(Context c, String name) {
        int look = HomeSettings.get(c, HomeSettings.LOOK);
        if (name != null) look = SkyView.WALLPAPER;
        else if (look == SkyView.WALLPAPER) look = 0;
        HomeSettings.prefs(c).edit().putString(KEY, name).putInt(HomeSettings.LOOK, look)
                .putInt("settings_version", HomeSettings.version(c) + 1).apply();
        SkyView.refreshAll(look);
    }

    // ---------------------------------------------------------------- the background bitmap

    private static Bitmap shown;
    private static String shownKey, loadingKey;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The background as a GPU bitmap, or null while it loads (then {@code ready} runs) or if none. */
    static Bitmap backdrop(Context c, Runnable ready) {
        String name = background(c);
        if (name == null) return null;
        File f = file(name);
        String key = name + "@" + f.lastModified();
        synchronized (Wallpapers.class) {
            if (key.equals(shownKey)) return shown;
            if (key.equals(loadingKey)) return null;
            loadingKey = key;
        }
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Bitmap b = bake(f);
            main.post(() -> {
                synchronized (Wallpapers.class) {
                    if (!key.equals(loadingKey)) return;
                    // The old one is left to the GC: a view may still be drawing it this frame.
                    shown = b;
                    shownKey = key;
                    loadingKey = null;
                }
                ready.run();
            });
        }, "wallpaper").start();
        return null;
    }

    /**
     * Screen-sized, then moved to the GPU. Darkened where words sit over it, baked in so it costs
     * nothing per frame: bands at the top and bottom, and soft shade behind the clock and date (top
     * left), the top bar (top right) and the app's or title's name over the apps (bottom left).
     * The middle of the picture keeps its colour.
     */
    private static Bitmap bake(File f) {
        Bitmap src = decode(f, W, H);
        if (src == null) return null;
        Bitmap b = fill(src, W, H);
        src.recycle();
        Canvas cv = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, H, new int[]{0x99000000, 0x1F000000, 0x1F000000, 0x8C000000, 0xD9000000},
                new float[]{0f, 0.28f, 0.45f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, p);
        shade(cv, W * 0.17f, H * 0.09f, W * 0.46f, 0xB3);
        shade(cv, W * 0.86f, H * 0.08f, W * 0.24f, 0x73);
        shade(cv, W * 0.14f, H * 0.62f, W * 0.40f, 0x8C);
        Bitmap gpu = b.copy(Bitmap.Config.HARDWARE, false);
        if (gpu == null) return b;
        b.recycle();
        return gpu;
    }

    /** A soft oval of shade, twice as wide as tall, fading out from {@code alpha} at its centre. */
    private static void shade(Canvas cv, float cx, float cy, float r, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx, cy, r, new int[]{alpha << 24, (alpha / 2) << 24, 0},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.save();
        cv.scale(1f, 0.5f, cx, cy);
        cv.drawCircle(cx, cy, r, p);
        cv.restore();
    }

    // ---------------------------------------------------------------- decoding

    /** Decode at least {@code w}x{@code h} (power-of-two downsampled), upright per EXIF. */
    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        int deg = 0;
        try {
            int or = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            deg = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (IOException ignored) { }
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /** {@code src} centre-cropped to w:h and scaled to exactly w x h. */
    private static Bitmap fill(Bitmap src, int w, int h) {
        int sw = src.getWidth(), sh = src.getHeight();
        Rect crop = sw * h > sh * w
                ? new Rect((sw - sh * w / h) / 2, 0, (sw + sh * w / h) / 2, sh)
                : new Rect(0, (sh - sw * h / w) / 2, sw, (sh + sw * h / w) / 2);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, crop, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }
}
