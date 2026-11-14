package dev.bhawesh.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The screensaver, as a regular Android dream (what Amazon's is too), made the system's
 * screensaver by {@link #apply}. Styles:
 * Clock: time, date and weather over the home background, hopping to a new spot every minute.
 * Posters: what's trending on the apps here, one backdrop at a time, with its name and app.
 * Photos: the pictures in {@link #PHOTOS}, crossfading, a small clock in the corner.
 * Ambient: just the background, slowly moving, and a small clock.
 * Shuffle: a different one of those each time.
 * Images go through HeroView (baked, GPU-held, one at a time, crossfaded); nothing redraws the
 * full screen continuously except the sky at its own low frame rate.
 */
public class Screensaver extends DreamService {
    static final String[] STYLES = {"Off", "Clock", "Posters", "Photos", "Ambient", "Shuffle"};
    private static final int CLOCK = 1, POSTERS = 2, PHOTOS_STYLE = 3, AMBIENT = 4, SHUFFLE = 5;
    static final File PHOTOS = new File("/sdcard/Pictures/tvhome");
    private static final String AMAZON = "com.amazon.ftv.screensaver/.app.services.ScreensaverService";
    private static final long CLOCK_HOP_MS = 60_000, SLIDE_MS = 12_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private int style;
    private SkyView sky;
    private HeroView hero;
    private Look.TopBar bar;
    private LinearLayout block;
    private TextView name, where, smallTime, smallDate;
    private JSONArray titles;
    private final List<File> photos = new ArrayList<>();
    private int index;

    /** Point the system screensaver at ours, or back at Amazon's for "Off" (WRITE_SECURE_SETTINGS, granted over adb). */
    static void apply(Context c) {
        boolean ours = HomeSettings.get(c, HomeSettings.SAVER) != 0;
        String comp = ours ? new ComponentName(c, Screensaver.class).flattenToShortString() : AMAZON;
        try {
            Settings.Secure.putString(c.getContentResolver(), "screensaver_components", comp);
        } catch (SecurityException notGranted) { }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key wakes, back to what was on screen
        setFullscreen(true);
        style = HomeSettings.get(this, HomeSettings.SAVER);
        if (style == PHOTOS_STYLE || style == SHUFFLE) loadPhotoList();
        if (style == SHUFFLE) {
            int[] pick = photos.isEmpty() ? new int[]{CLOCK, POSTERS, AMBIENT} : new int[]{CLOCK, POSTERS, PHOTOS_STYLE, AMBIENT};
            style = pick[rnd.nextInt(pick.length)];
        }
        if (style == PHOTOS_STYLE && photos.isEmpty()) style = CLOCK;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        if (style == POSTERS) buildPosters(root);
        else if (style == PHOTOS_STYLE) buildPhotos(root);
        else if (style == AMBIENT) buildAmbient(root);
        else buildClock(root);
        setContentView(root);
    }

    // ---------------------------------------------------------------- clock and ambient

    private void addSky(FrameLayout root) {
        sky = new SkyView(this);
        sky.setLook(HomeSettings.get(this, HomeSettings.LOOK));
        sky.setAccent(MainActivity.accent, 0.45f, false);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildClock(FrameLayout root) {
        addSky(root);
        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        bar = new Look.TopBar(this);
        bar.clock.setTextSize(96);
        bar.ampm.setTextSize(22);
        bar.weather.setText(getSharedPreferences("weather", MODE_PRIVATE).getString("line", ""));
        block.addView(bar.view);
        root.addView(block, new FrameLayout.LayoutParams(-2, -2));
    }

    private void buildAmbient(FrameLayout root) {
        addSky(root);
        root.addView(smallClock(), cornerLp(Gravity.BOTTOM | Gravity.END));
    }

    /** Time over date, small, for the corner of Photos and Ambient. */
    private View smallClock() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.END);
        smallTime = Look.text(this, Look.serif(this), 40, Color.WHITE);
        smallDate = Look.text(this, Look.sans(this, 500), 14, 0xB3FFFFFF);
        for (TextView t : new TextView[]{smallTime, smallDate}) Look.shadow(t, Look.dp(this, 12), 0, 0, 0x99000000);
        c.addView(smallTime);
        c.addView(smallDate);
        updateSmallClock();
        return c;
    }

    private void updateSmallClock() {
        if (smallTime == null) return;
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        smallTime.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
        smallDate.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
    }

    private FrameLayout.LayoutParams cornerLp(int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        int m = Look.dp(this, Look.PAD_X);
        lp.setMargins(m, m, m, Look.dp(this, 48));
        return lp;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateSmallClock();
            handler.postDelayed(this, 30_000);
        }
    };

    private final Runnable hop = new Runnable() {
        @Override public void run() {
            bar.update(Screensaver.this);
            View parent = (View) block.getParent();
            int maxX = Math.max(0, parent.getWidth() - block.getWidth()), maxY = Math.max(0, parent.getHeight() - block.getHeight());
            float x = maxX * (0.08f + 0.84f * rnd.nextFloat()), y = maxY * (0.1f + 0.8f * rnd.nextFloat());
            // Fade out, jump, fade in: a step, not a slide, so nothing redraws in between.
            block.animate().alpha(0f).setDuration(Look.ms(Screensaver.this, 900)).withEndAction(() -> {
                block.setTranslationX(x);
                block.setTranslationY(y);
                block.animate().alpha(1f).setDuration(Look.ms(Screensaver.this, 1200)).start();
            }).start();
            handler.postDelayed(this, CLOCK_HOP_MS);
        }
    };

    // ---------------------------------------------------------------- posters and photos

    private void buildPosters(FrameLayout root) {
        hero = new HeroView(this);
        root.addView(hero, new FrameLayout.LayoutParams(-1, -1));
        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        where = Look.text(this, Look.sans(this, 600), 15, 0x99FFFFFF);
        name = Look.text(this, Look.serif(this), 52, Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        block.addView(where);
        block.addView(name, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(Look.dp(this, Look.PAD_X), 0, Look.dp(this, Look.PAD_X), Look.dp(this, 64));
        root.addView(block, lp);
        bar = new Look.TopBar(this);
        bar.weather.setText(getSharedPreferences("weather", MODE_PRIVATE).getString("line", ""));
        root.addView(bar.view, bar.lp(this));
    }

    private void buildPhotos(FrameLayout root) {
        hero = new HeroView(this);
        root.addView(hero, new FrameLayout.LayoutParams(-1, -1));
        root.addView(smallClock(), cornerLp(Gravity.BOTTOM | Gravity.END));
    }

    private void loadPhotoList() {
        File[] files = PHOTOS.listFiles((d, n) -> n.matches("(?i).+\\.(jpe?g|png|webp)"));
        if (files != null) Collections.addAll(photos, files);
        Collections.shuffle(photos, rnd);
    }

    /** Load the next image off the main thread, then crossfade to it. */
    private final Runnable nextSlide = new Runnable() {
        @Override public void run() {
            int w = getResources().getDisplayMetrics().widthPixels / 2, h = getResources().getDisplayMetrics().heightPixels / 2;
            new Thread(() -> {
                try {
                    if (style == PHOTOS_STYLE) showPhoto(w, h);
                    else showPoster(w, h);
                } catch (Exception ignored) { }
            }, "saver").start();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    private void showPhoto(int w, int h) {
        File f = photos.get(index++ % photos.size());
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
        if (src == null) return;
        Bitmap b = HeroView.bakePhoto(src, w, h);
        src.recycle();
        handler.post(() -> { if (hero == null) b.recycle(); else { hero.setImage(b); updateSmallClock(); } });
    }

    private void showPoster(int w, int h) throws Exception {
        if (titles == null) titles = Catalog.recs(this);
        if (titles.length() == 0) return;
        JSONObject t = titles.getJSONObject(index++ % titles.length());
        // Full screen: JustWatch's 1920 wide size, decoded whole.
        String url = t.optString("backdrop", null);
        Bitmap src = Net.image(this, url == null ? null : url.replace("/s1440/", "/s1920/"), w);
        if (src == null) return;
        Bitmap b = HeroView.bake(src, w, h);
        src.recycle();
        JSONArray o = t.optJSONArray("offers");
        String app = o != null && o.length() > 0 ? o.getJSONObject(0).optString("label") : "";
        handler.post(() -> {
            if (hero == null) { b.recycle(); return; }
            hero.setImage(b);
            bar.update(this);
            name.setText(t.optString("title"));
            where.setText(app.isEmpty() ? "" : "On " + app);
            block.setAlpha(0f);
            block.animate().alpha(1f).setDuration(Look.ms(this, 900)).start();
        });
    }

    // ---------------------------------------------------------------- lifecycle

    private static Screensaver showing;

    static boolean isShowing() { return showing != null; }

    /** End the screensaver if ours is up (the phone opened something, so the TV is in use). */
    static void dismiss() {
        Screensaver s = showing;
        if (s != null) s.handler.post(s::finish);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        showing = this;
        if (hero != null) handler.post(nextSlide);
        if (sky != null) sky.setRunning(true);
        if (style == CLOCK) handler.postDelayed(hop, 1000);
        if (smallTime != null) handler.post(tick);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        if (showing == this) showing = null;
        handler.removeCallbacksAndMessages(null);
        if (sky != null) sky.setRunning(false);
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (hero != null) hero.release();
        hero = null;
        super.onDetachedFromWindow();
    }
}
