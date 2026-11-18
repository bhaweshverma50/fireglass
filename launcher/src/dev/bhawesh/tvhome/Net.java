package dev.bhawesh.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Plain GETs, and images through a small disk cache, decoded no larger than needed. */
final class Net {
    private Net() { }

    static String get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        // Desktop browser: YouTube sends others a stripped page or its mobile site.
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
        h.setRequestProperty("Accept-Language", "en");
        try (InputStream in = h.getInputStream()) {
            return Catalog.readAll(in);
        } finally {
            h.disconnect();
        }
    }

    /** Image at least {@code width} px wide (downsampled by powers of two), RGB_565, or null. */
    static Bitmap image(Context c, String url, int width) { return image(c, url, width, ""); }

    /** Same, for a server that wants a session cookie (Booth). */
    static Bitmap image(Context c, String url, int width, String cookie) {
        if (url == null) return null;
        try {
            File f = cached(c, url, cookie);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= width) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            // 16-bit colour bands the dark gradients of big pictures; small ones don't show it.
            o.inPreferredConfig = width >= 600 ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    private static File cached(Context c, String url, String cookie) throws Exception {
        File dir = new File(c.getCacheDir(), "img");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
        StringBuilder name = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"))) name.append(String.format("%02x", b));
        File f = new File(dir, name + ".jpg");
        if (f.length() > 0) return f;
        File tmp = new File(dir, name + ".part");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(15000);
        if (!cookie.isEmpty()) h.setRequestProperty("Cookie", cookie);
        try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            h.disconnect();
        }
        if (!tmp.renameTo(f)) throw new IOException("cache write");
        return f;
    }
}
