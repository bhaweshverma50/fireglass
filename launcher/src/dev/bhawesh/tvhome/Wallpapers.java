package dev.bhawesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
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
                    .put("modified", f.lastModified()).put("uploaded", f.getName().startsWith("up-"))
                    .put("rotate", rotates(c, f.getName())));
        }
        String bg = background(c);
        Set<String> rot = rotation(c);
        return new JSONObject().put("items", items).put("background", bg == null ? JSONObject.NULL : bg)
                .put("look", HomeSettings.get(c, HomeSettings.LOOK))
                .put("rotation", new JSONObject().put("every", every(c))
                        .put("names", rot == null ? JSONObject.NULL : new JSONArray(new ArrayList<>(rot))));
    }

    /** A 320x180 JPEG of the picture, for the phone's grid. */
    static byte[] thumb(String name) {
        File f = file(name);
        if (f == null) return null;
        // Kept on disk: decoding a 1080p photo takes a few hundred ms on a Fire TV Stick, and the
        // TV's own picker shows dozens.
        File cache = new File(Screensaver.PHOTOS.getParentFile(), ".tvhome-thumbs/" + name + "@" + f.lastModified() + ".jpg");
        if (cache.length() > 0) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(cache)) {
                byte[] b = new byte[(int) cache.length()];
                int n = 0;
                while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; }
                if (n == b.length) return b;
            } catch (IOException ignored) { }
        }
        byte[] fresh = thumbFresh(f);
        if (fresh != null) {
            cache.getParentFile().mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(cache)) { out.write(fresh); } catch (IOException ignored) { }
        }
        return fresh;
    }

    private static byte[] thumbFresh(File f) {
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

    // ---------------------------------------------------------------- rotation

    /** Every picture's name, newest first. */
    static List<String> names() {
        File[] files = DIR.listFiles((d, n) -> n.matches(NAME));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }

    /** The pictures chosen to rotate, or null when none were chosen (then all of them do). */
    static Set<String> rotation(Context c) {
        Set<String> s = HomeSettings.prefs(c).getStringSet("wall_rotation", null);
        return s == null ? null : new HashSet<>(s);
    }

    static boolean rotates(Context c, String name) {
        Set<String> s = rotation(c);
        return s == null || s.contains(name);
    }

    /** null: every picture rotates. */
    static void setRotation(Context c, Set<String> names) {
        SharedPreferences.Editor e = HomeSettings.prefs(c).edit().remove("wall_bag");
        if (names == null) e.remove("wall_rotation"); else e.putStringSet("wall_rotation", new HashSet<>(names));
        e.apply();
    }

    static void toggleRotation(Context c, String name) {
        Set<String> s = rotation(c);
        if (s == null) s = new HashSet<>(names());
        if (!s.remove(name)) s.add(name);
        setRotation(c, s);
    }

    /** Minutes between changes (Customise home: Wallpaper changes); 0 is never. */
    static int every(Context c) { return HomeSettings.ROTATE_MIN[HomeSettings.get(c, HomeSettings.ROTATE)]; }

    /** Set the interval; anything but never also puts a picture up if the background is a sky. */
    static void setEvery(Context c, int minutes) {
        int idx = 0;
        for (int i = 0; i < HomeSettings.ROTATE_MIN.length; i++) if (HomeSettings.ROTATE_MIN[i] == minutes) idx = i;
        HomeSettings.prefs(c).edit().putInt(HomeSettings.ROTATE, idx).putInt("settings_version", HomeSettings.version(c) + 1).apply();
        if (idx > 0 && (HomeSettings.get(c, HomeSettings.LOOK) != SkyView.WALLPAPER || background(c) == null)) next(c);
    }

    /**
     * The next picture in the rotation, made the background. A shuffled bag: every picture once,
     * in random order, before any comes round again. Null if there's no picture to show.
     */
    static synchronized String next(Context c) {
        List<String> pool = new ArrayList<>();
        for (String n : names()) if (rotates(c, n)) pool.add(n);
        if (pool.isEmpty()) pool = names(); // all taken out of the rotation: still show something
        if (pool.isEmpty()) return null;
        SharedPreferences p = HomeSettings.prefs(c);
        List<String> bag = new ArrayList<>();
        for (String n : p.getString("wall_bag", "").split("/")) if (pool.contains(n)) bag.add(n);
        String cur = background(c);
        if (bag.isEmpty()) {
            bag.addAll(pool);
            java.util.Collections.shuffle(bag);
            if (bag.size() > 1 && bag.get(0).equals(cur)) bag.add(bag.remove(0));
        }
        String pick = bag.remove(0);
        p.edit().putString("wall_bag", android.text.TextUtils.join("/", bag)).apply();
        setBackground(c, pick);
        return pick;
    }

    /**
     * Wallpaper changes: when it's time, the next picture. Called each minute while the home
     * shows, and when it comes back.
     */
    static void rotate(Context c) {
        int every = every(c);
        if (every == 0 || HomeSettings.get(c, HomeSettings.LOOK) != SkyView.WALLPAPER) return;
        long at = HomeSettings.prefs(c).getLong("wall_rotated_at", 0);
        if (at == 0) { HomeSettings.prefs(c).edit().putLong("wall_rotated_at", System.currentTimeMillis()).apply(); return; }
        if (System.currentTimeMillis() - at >= every * 60_000L) next(c);
    }

    /** Make {@code name} the home's background (Background: Wallpaper), or none (back to Live sky). */
    static void setBackground(Context c, String name) {
        int look = HomeSettings.get(c, HomeSettings.LOOK);
        if (name != null) look = SkyView.WALLPAPER;
        else if (look == SkyView.WALLPAPER) look = 0;
        // Any pick (the phone, the TV, the rotation) starts the interval over.
        HomeSettings.prefs(c).edit().putString(KEY, name).putInt(HomeSettings.LOOK, look)
                .putLong("wall_rotated_at", System.currentTimeMillis())
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
        return Look.gpu(b);
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

    // ---------------------------------------------------------------- the TV's own picker

    /**
     * Customise home > Wallpapers: every picture as a thumbnail. OK puts it in the rotation or
     * takes it out; holding OK shows it now. All and None at the top. No phone needed.
     */
    public static final class Picker extends android.app.Activity {
        private static final int PER_ROW = 4;
        private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newFixedThreadPool(2);
        private final List<Runnable> refresh = new ArrayList<>();
        private android.widget.TextView sub;

        @Override protected void onCreate(android.os.Bundle b) {
            super.onCreate(b);
            getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            android.widget.FrameLayout root = new android.widget.FrameLayout(this);
            root.setBackgroundColor(android.graphics.Color.BLACK);
            SkyView sky = new SkyView(this);
            sky.setLook(0);
            sky.setAccent(MainActivity.accent, 0.4f, false);
            root.addView(sky, new android.widget.FrameLayout.LayoutParams(-1, -1));
            android.view.View dim = new android.view.View(this);
            dim.setBackgroundColor(0xB3000000);
            root.addView(dim, new android.widget.FrameLayout.LayoutParams(-1, -1));

            android.widget.LinearLayout col = new android.widget.LinearLayout(this);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setClipChildren(false);
            // Side margins inside the scroller, not around it: a lifted tile at either edge isn't cut.
            col.setPadding(0, dp(Look.PAD_TOP), 0, 0);

            android.widget.LinearLayout head = new android.widget.LinearLayout(this);
            head.setGravity(android.view.Gravity.BOTTOM);
            android.widget.LinearLayout titles = new android.widget.LinearLayout(this);
            titles.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.TextView title = Look.text(this, Look.serif(this), 40, android.graphics.Color.WHITE);
            title.setText("Wallpapers");
            titles.addView(title);
            sub = Look.text(this, Look.sans(this, 500), 14, 0xA6FFFFFF);
            titles.addView(sub);
            head.addView(titles, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            head.addView(pill("All in rotation", () -> { setRotation(this, null); update(); }));
            android.widget.LinearLayout.LayoutParams np = new android.widget.LinearLayout.LayoutParams(-2, -2);
            np.setMargins(dp(12), 0, 0, 0);
            head.addView(pill("None", () -> { setRotation(this, new HashSet<>()); update(); }), np);
            android.widget.LinearLayout.LayoutParams hlp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            hlp.setMargins(dp(Look.PAD_X), 0, dp(Look.PAD_X), 0);
            col.addView(head, hlp);

            android.widget.ScrollView sv = new MainActivity.HomeScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            sv.setPadding(0, dp(24), 0, dp(40));
            sv.setClipChildren(false);
            android.widget.LinearLayout grid = new android.widget.LinearLayout(this);
            grid.setOrientation(android.widget.LinearLayout.VERTICAL);
            grid.setClipChildren(false);
            grid.setClipToPadding(false);
            grid.setPadding(dp(Look.PAD_X), 0, dp(Look.PAD_X), 0);
            int gap = dp(22);
            int w = (getResources().getDisplayMetrics().widthPixels - 2 * dp(Look.PAD_X) - (PER_ROW - 1) * gap) / PER_ROW;
            android.widget.LinearLayout row = null;
            List<String> all = names();
            for (int i = 0; i < all.size(); i++) {
                if (i % PER_ROW == 0) {
                    row = new android.widget.LinearLayout(this);
                    row.setClipChildren(false);
                    android.widget.LinearLayout.LayoutParams rp = new android.widget.LinearLayout.LayoutParams(-1, -2);
                    rp.setMargins(0, i == 0 ? 0 : gap, 0, 0);
                    grid.addView(row, rp);
                }
                android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(w, w * 9 / 16);
                tp.setMargins(i % PER_ROW == 0 ? 0 : gap, 0, 0, 0);
                row.addView(tile(all.get(i), w), tp);
            }
            if (all.isEmpty()) {
                android.widget.TextView none = Look.text(this, Look.sans(this, 500), 16, 0xCCFFFFFF);
                none.setText("No pictures yet. Add some from the phone app: Wallpapers.");
                grid.addView(none);
            }
            sv.addView(grid);
            col.addView(sv, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
            root.addView(col, new android.widget.FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            update();
            if (grid.getChildCount() > 0 && grid.getChildAt(0) instanceof android.widget.LinearLayout)
                ((android.widget.LinearLayout) grid.getChildAt(0)).getChildAt(0).requestFocus();
        }

        private void update() {
            for (Runnable r : refresh) r.run();
            int all = names().size();
            Set<String> rot = rotation(this);
            int n = rot == null ? all : 0;
            if (rot != null) for (String x : names()) if (rot.contains(x)) n++;
            int every = every(this);
            sub.setText(n + " of " + all + " in the rotation  ·  "
                    + (every == 0 ? "Wallpaper changes is off (Customise home)" : "a new one every " + (every == 15 ? "15 minutes" : every == 60 ? "hour" : "day")));
        }

        private android.view.View tile(String name, int w) {
            android.widget.FrameLayout t = new android.widget.FrameLayout(this);
            t.setFocusable(true);
            t.setClickable(true);
            t.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2A2A33, 0xFF121217}));
            Look.roundClip(t, dp(Look.TILE_RADIUS));
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            t.addView(img, new android.widget.FrameLayout.LayoutParams(-1, -1));
            // In the rotation: a white disc with a check, top right.
            android.view.View check = new android.view.View(this) {
                private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                private final android.graphics.Path path = new android.graphics.Path();
                @Override protected void onDraw(android.graphics.Canvas c) {
                    float s = getWidth();
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor(0xF2FFFFFF);
                    c.drawCircle(s / 2, s / 2, s / 2, p);
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(s * 0.12f);
                    p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                    p.setColor(0xFF16161C);
                    path.reset();
                    path.moveTo(s * 0.28f, s * 0.52f);
                    path.lineTo(s * 0.44f, s * 0.67f);
                    path.lineTo(s * 0.73f, s * 0.36f);
                    c.drawPath(path, p);
                }
            };
            android.widget.FrameLayout.LayoutParams cp = new android.widget.FrameLayout.LayoutParams(dp(26), dp(26), android.view.Gravity.TOP | android.view.Gravity.END);
            cp.setMargins(0, dp(10), dp(10), 0);
            t.addView(check, cp);
            android.widget.TextView now = Look.text(this, Look.sans(this, 600), 11, android.graphics.Color.WHITE);
            now.setText("Showing");
            now.setPadding(dp(8), dp(3), dp(8), dp(3));
            android.graphics.drawable.GradientDrawable nb = new android.graphics.drawable.GradientDrawable();
            nb.setCornerRadius(dp(8));
            nb.setColor(0xCC000000);
            now.setBackground(nb);
            android.widget.FrameLayout.LayoutParams nlp = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.START);
            nlp.setMargins(dp(10), 0, 0, dp(10));
            t.addView(now, nlp);
            android.view.View rim = new android.view.View(this);
            android.graphics.drawable.GradientDrawable rg = new android.graphics.drawable.GradientDrawable();
            rg.setCornerRadius(dp(Look.TILE_RADIUS));
            rg.setStroke(dp(2), 0xE6FFFFFF);
            rim.setBackground(rg);
            rim.setAlpha(0f);
            t.addView(rim, new android.widget.FrameLayout.LayoutParams(-1, -1));

            Runnable show = () -> {
                boolean in = rotates(this, name);
                check.setVisibility(in ? android.view.View.VISIBLE : android.view.View.GONE);
                img.setAlpha(in ? 1f : 0.45f);
                now.setVisibility(name.equals(background(this)) && HomeSettings.get(this, HomeSettings.LOOK) == SkyView.WALLPAPER
                        ? android.view.View.VISIBLE : android.view.View.GONE);
            };
            refresh.add(show);
            t.setOnClickListener(v -> { toggleRotation(this, name); update(); });
            t.setOnLongClickListener(v -> { setBackground(this, name); update(); return true; });
            t.setOnFocusChangeListener((v, has) -> {
                t.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? dp(12) : 0)
                        .setDuration(has ? 260 : 180).start();
                rim.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            });
            io.execute(() -> {
                byte[] jpg = thumb(name);
                if (jpg == null) return;
                Bitmap bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                if (bm != null) runOnUiThread(() -> img.setImageBitmap(bm));
            });
            return t;
        }

        private android.view.View pill(String label, Runnable act) {
            android.widget.TextView p = Buttons.sheetRow(this, label);
            p.setPadding(dp(18), dp(10), dp(18), dp(10));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(0x26FFFFFF);
            p.setBackground(bg);
            p.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0xF2FFFFFF : 0x26FFFFFF);
                p.setTextColor(has ? 0xFF16161C : android.graphics.Color.WHITE);
            });
            p.setOnClickListener(v -> act.run());
            return p;
        }

        @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }

        private int dp(float v) { return Look.dp(this, v); }
    }
}
