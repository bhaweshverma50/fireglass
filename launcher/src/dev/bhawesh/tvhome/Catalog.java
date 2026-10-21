package dev.bhawesh.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Titles and where to watch them, from JustWatch's public GraphQL API (unofficial: no key, but it
 * can change; everything degrades to "no recommendations"). Only offers on apps installed here
 * count, and opening one goes straight to the title inside that app (tested per app on this stick).
 */
final class Catalog {
    private Catalog() { }

    private static final String TAG = "TvHome";
    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String IMG = "https://images.justwatch.com";
    private static final long RECS_TTL_MS = 6 * 3600 * 1000L;

    /** JustWatch package short name -> Fire TV app. */
    private static final Map<String, String> APPS = new LinkedHashMap<>();
    static {
        APPS.put("nfx", "com.netflix.ninja");
        APPS.put("prv", "com.amazon.firebat");
        APPS.put("jhs", "in.startv.hotstar");
        APPS.put("zee", "com.zee5.amazon");
        APPS.put("snl", "com.onemainstream.sonyliv.android");
        APPS.put("atp", "com.apple.atve.amazon.appletv");
        APPS.put("cru", "com.crunchyroll.crunchyroid");
    }

    private static final String FIELDS = "id objectType content(country: $c, language: $l) { title originalReleaseYear "
            + "shortDescription posterUrl backdrops { backdropUrl } genres { translation(language: $l) } "
            + "scoring { imdbScore } } offers(country: $c, platform: WEB) { monetizationType standardWebURL "
            + "deeplinkURL(platform: ANDROID_TV) package { shortName clearName } }";

    private static final Map<String, JSONObject> byId = new HashMap<>();
    private static JSONArray recs;
    private static long recsAt;

    // ---------------------------------------------------------------- api routes

    static RemoteServer.Res handle(Context c, RemoteServer srv, String method, String path, String q,
                                   JSONObject body) throws Exception {
        switch (path) {
            case "/api/search":
                return RemoteServer.json(200, search(c, q == null ? "" : q, 10));
            case "/api/recs":
                return RemoteServer.json(200, recs(c));
            case "/api/recent":
                return RemoteServer.json(200, recent(c));
            case "/api/play":
                return RemoteServer.json(200, play(c, body.optString("id", null), body.optString("title", null),
                        body.isNull("pkg") ? null : body.optString("pkg", null)));
            default:
                return RemoteServer.json(404, RemoteServer.err("not_found"));
        }
    }

    // ---------------------------------------------------------------- queries

    static JSONArray search(Context c, String query, int n) throws IOException, JSONException {
        JSONObject f = new JSONObject().put("searchQuery", query);
        return titles(c, query(f, n, null));
    }

    /** Trending on the apps installed here; cached for 6 hours in memory and on disk. */
    static synchronized JSONArray recs(Context c) throws JSONException {
        if (recs != null && System.currentTimeMillis() - recsAt < RECS_TTL_MS) return recs;
        File cache = new File(c.getCacheDir(), "recs.json");
        try {
            JSONArray pk = new JSONArray();
            for (Map.Entry<String, String> e : APPS.entrySet()) if (installed(c, e.getValue())) pk.put(e.getKey());
            JSONArray t = titles(c, query(new JSONObject().put("packages", pk), 24, "TRENDING"));
            JSONArray withArt = new JSONArray();
            for (int i = 0; i < t.length(); i++) {
                JSONObject o = t.getJSONObject(i);
                if (o.has("backdrop") && o.getJSONArray("offers").length() > 0) withArt.put(o);
            }
            recs = withArt;
            recsAt = System.currentTimeMillis();
            Files.write(cache.toPath(), recs.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "recs offline: " + e);
            if (recs == null && cache.exists()) {
                try { recs = new JSONArray(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8)); }
                catch (IOException ignored) { }
            }
            if (recs == null) recs = new JSONArray();
        }
        for (int i = 0; i < recs.length(); i++) byId.put(recs.getJSONObject(i).getString("id"), recs.getJSONObject(i));
        return recs;
    }

    private static JSONArray query(JSONObject filter, int n, String sort) throws IOException, JSONException {
        String q = "query Q($f: TitleFilter!, $c: Country!, $l: Language!, $n: Int!) { popularTitles(country: $c, "
                + "filter: $f, first: $n" + (sort != null ? ", sortBy: " + sort : "") + ") { edges { node { "
                + FIELDS + " } } } }";
        JSONObject vars = new JSONObject().put("f", filter).put("c", "IN").put("l", "en").put("n", n);
        JSONObject res = post(new JSONObject().put("query", q).put("variables", vars));
        if (res.has("errors")) throw new IOException("justwatch: " + res.getJSONArray("errors").getJSONObject(0).optString("message"));
        return res.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
    }

    /** JustWatch nodes -> the API's title shape (docs/remote-api.md), offers limited to installed apps. */
    private static JSONArray titles(Context c, JSONArray edges) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.getJSONObject(i).getJSONObject("node");
            JSONObject ct = node.getJSONObject("content");
            JSONObject t = new JSONObject()
                    .put("id", node.getString("id"))
                    .put("title", ct.optString("title"))
                    .put("year", ct.optInt("originalReleaseYear"))
                    .put("kind", "SHOW".equals(node.optString("objectType")) ? "tv" : "movie")
                    .put("overview", ct.optString("shortDescription"));
            JSONObject sc = ct.optJSONObject("scoring");
            if (sc != null && !sc.isNull("imdbScore")) t.put("rating", sc.optDouble("imdbScore"));
            JSONArray genres = new JSONArray(), g = ct.optJSONArray("genres");
            for (int k = 0; g != null && k < Math.min(2, g.length()); k++) genres.put(g.getJSONObject(k).optString("translation"));
            t.put("genres", genres);
            String poster = ct.optString("posterUrl", "");
            if (!poster.isEmpty() && !ct.isNull("posterUrl")) t.put("poster", image(poster, "s592"));
            JSONArray bd = ct.optJSONArray("backdrops");
            if (bd != null && bd.length() > 0) {
                String path = bd.getJSONObject(0).getString("backdropUrl");
                t.put("backdrop", image(path, "s1440")).put("thumb", image(path, "s640"));
            }

            JSONArray offers = new JSONArray();
            Map<String, Boolean> seen = new HashMap<>();
            JSONArray of = node.optJSONArray("offers");
            for (int k = 0; of != null && k < of.length(); k++) {
                JSONObject o = of.getJSONObject(k);
                String m = o.optString("monetizationType");
                if (!m.equals("FLATRATE") && !m.equals("FREE") && !m.equals("ADS")) continue;
                JSONObject p = o.getJSONObject("package");
                String pkg = APPS.get(p.optString("shortName"));
                if (pkg == null || seen.containsKey(pkg) || !installed(c, pkg)) continue;
                seen.put(pkg, true);
                JSONObject offer = new JSONObject().put("pkg", pkg).put("label", label(c, pkg, p.optString("clearName")))
                        .put("url", o.optString("standardWebURL"));
                if (!o.isNull("deeplinkURL")) offer.put("deeplink", o.optString("deeplinkURL"));
                offers.put(offer);
            }
            t.put("offers", offers);
            byId.put(t.getString("id"), t);
            out.put(t);
        }
        return out;
    }

    private static String image(String path, String profile) {
        return IMG + path.replace("{profile}", profile).replace("{format}", "jpg");
    }

    // ---------------------------------------------------------------- play

    /** Resolve a title (by id, else by name), open it in the given app or the first one that has it. */
    static final String YOUTUBE = "com.amazon.firetv.youtube";
    static final String HOTSTAR = "in.startv.hotstar";

    static JSONObject play(Context c, String id, String title, String pkg) throws Exception {
        // YouTube isn't in the catalogue (and its TV app ignores search links): find the video here.
        if (YOUTUBE.equals(pkg) && title != null && !title.isEmpty()) return playYouTube(c, title);
        JSONObject t = id != null ? byId.get(id) : null;
        JSONArray candidates = new JSONArray();
        if (t == null && title != null && !title.isEmpty()) {
            candidates = search(c, title, 6);
            for (int i = 0; i < candidates.length() && t == null; i++) {
                JSONObject x = candidates.getJSONObject(i);
                if (x.getString("title").equalsIgnoreCase(title.trim())) t = x;
            }
            if (t == null && candidates.length() > 0) t = candidates.getJSONObject(0);
        }
        if (t == null) {
            // Not a show or film anyone streams: it's probably a video, so try YouTube.
            if (pkg == null && title != null && installed(c, YOUTUBE)) return playYouTube(c, title);
            return RemoteServer.err("not_found");
        }
        JSONArray offers = t.getJSONArray("offers");
        JSONObject pick = null;
        for (int i = 0; i < offers.length(); i++) {
            JSONObject o = offers.getJSONObject(i);
            if (pkg == null || pkg.equals(o.getString("pkg"))) { pick = o; break; }
        }
        if (pick == null) {
            JSONObject e = RemoteServer.err("not_on_your_apps");
            return e.put("candidates", candidates.length() > 0 ? candidates : new JSONArray().put(t));
        }
        if (!open(c, pick)) return RemoteServer.err("cannot_open");
        remember(c, new JSONObject(t.toString()), pick.getString("pkg"));
        return new JSONObject().put("ok", true).put("opened", new JSONObject().put("title", t.getString("title"))
                .put("pkg", pick.getString("pkg")).put("how", "deeplink"));
    }

    /**
     * The top video for {@code query} on YouTube's site: {id, thumbnail}. The thumbnail is the widest
     * one the results list for it (16:9, no letterbox bars), else the stock hqdefault.
     */
    static String[] youTubeHit(String query) throws Exception {
        String html = Net.get("https://www.youtube.com/results?hl=en&search_query=" + java.net.URLEncoder.encode(query, "UTF-8"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
        if (!m.find()) {
            m = java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
            if (!m.find()) return null;
        }
        String id = m.group(1), thumb = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        int at = html.indexOf("\"thumbnails\":[", m.end()), end = at < 0 ? -1 : html.indexOf(']', at);
        if (at >= 0 && end > at && at - m.end() < 400) {
            java.util.regex.Matcher u = java.util.regex.Pattern.compile("\"url\":\"(https://i\\.ytimg\\.com/vi/" + id + "/[^\"]+)\"")
                    .matcher(html.substring(at, end));
            while (u.find()) thumb = u.group(1).replace("\\u0026", "&");
        }
        return new String[]{id, thumb};
    }

    /**
     * YouTube's own results for {@code query}, from the ytInitialData its results page carries:
     * [{id, title, channel, length, thumb}]. Fire TV's YouTube app has no search link to hand off to.
     */
    static JSONArray youTubeSearch(String query, int n) throws Exception {
        String html = Net.get("https://www.youtube.com/results?hl=en&search_query=" + java.net.URLEncoder.encode(query, "UTF-8"));
        int at = html.indexOf("var ytInitialData = "), end = at < 0 ? -1 : html.indexOf(";</script>", at);
        JSONArray out = new JSONArray();
        if (end < 0) return out;
        JSONArray sections = new JSONObject(html.substring(at + "var ytInitialData = ".length(), end))
                .getJSONObject("contents").getJSONObject("twoColumnSearchResultsRenderer").getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer").getJSONArray("contents");
        for (int i = 0; i < sections.length() && out.length() < n; i++) {
            JSONObject isr = sections.getJSONObject(i).optJSONObject("itemSectionRenderer");
            JSONArray items = isr == null ? null : isr.optJSONArray("contents");
            for (int k = 0; items != null && k < items.length() && out.length() < n; k++) {
                JSONObject v = items.getJSONObject(k).optJSONObject("videoRenderer");
                if (v == null) continue;
                JSONArray thumbs = v.getJSONObject("thumbnail").getJSONArray("thumbnails");
                JSONObject owner = v.optJSONObject("ownerText"), len = v.optJSONObject("lengthText");
                out.put(new JSONObject().put("id", v.getString("videoId"))
                        .put("title", v.getJSONObject("title").getJSONArray("runs").getJSONObject(0).optString("text"))
                        .put("channel", owner == null ? "" : owner.getJSONArray("runs").getJSONObject(0).optString("text"))
                        .put("length", len == null ? "" : len.optString("simpleText"))
                        .put("thumb", thumbs.getJSONObject(thumbs.length() - 1).getString("url")));
            }
        }
        return out;
    }

    static boolean playYouTubeVideo(Context c, String id) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id))
                    .setPackage(YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Search YouTube's site for {@code query} and play the top video in the YouTube app. */
    static JSONObject playYouTube(Context c, String query) throws Exception {
        String[] hit = youTubeHit(query);
        if (hit == null) return RemoteServer.err("not_found");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + hit[0]))
                .setPackage(YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return new JSONObject().put("ok", true).put("opened", new JSONObject().put("title", query)
                .put("pkg", YOUTUBE).put("how", "deeplink"));
    }

    /** Open an offer in its app. Crunchyroll wants its Android TV intent; the rest take their web link. */
    static boolean open(Context c, JSONObject offer) {
        String pkg = offer.optString("pkg");
        try {
            Intent i;
            if (offer.optString("url", "").isEmpty()) {
                // No link (history entry with no catalogue match): just open its app.
                i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } else if (pkg.equals("com.crunchyroll.crunchyroid") && offer.has("deeplink")) {
                i = Intent.parseUri(offer.getString("deeplink"), Intent.URI_INTENT_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getString("url")));
            }
            i.setPackage(pkg);
            i.setComponent(null);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open offer " + pkg, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- recent

    /**
     * Something played for a minute in {@code pkg} (from Watching): find it on JustWatch for art
     * and a link back into the same app, and put it first in history. Kept even without a match.
     */
    static void watched(Context c, String pkg, String show, String episode, String art) {
        try {
            // No show name (JioHotstar reports only "S1 E6 Bad Optics"): one card per app, opening the app.
            JSONObject hit = show.isEmpty() ? null : match(c, pkg, show);
            JSONObject entry = hit != null ? new JSONObject(hit.toString())
                    : new JSONObject().put("id", "app:" + pkg + ":" + show).put("title", show.isEmpty() ? episode : show)
                            .put("offers", new JSONArray().put(new JSONObject().put("pkg", pkg).put("label", label(c, pkg, pkg))));
            if (show.isEmpty()) episode = "";
            if (!episode.isEmpty()) entry.put("episode", episode);
            if (hit == null && art != null) entry.put("thumb", art).put("backdrop", art);
            if (hit == null && YOUTUBE.equals(pkg)) youTubeArt(entry, episode.isEmpty() ? show : episode + " " + show);
            remember(c, entry, pkg);
            Log.i(TAG, "watched " + entry.optString("title") + (episode.isEmpty() ? "" : " / " + episode) + " on " + pkg + (hit == null ? " (no match)" : ""));
        } catch (Exception e) {
            Log.w(TAG, "watched " + show, e);
        }
    }

    /** The catalogue title named exactly {@code show} that {@code pkg} offers, or null. */
    private static JSONObject match(Context c, String pkg, String show) throws IOException, JSONException {
        JSONArray found = search(c, show, 5);
        for (int i = 0; i < found.length(); i++) {
            JSONObject t = found.getJSONObject(i);
            if (!t.getString("title").equalsIgnoreCase(show)) continue;
            JSONArray o = t.getJSONArray("offers");
            for (int k = 0; k < o.length(); k++) if (pkg.equals(o.getJSONObject(k).optString("pkg"))) return t;
        }
        return null;
    }

    /**
     * An app's continue-watching card says "Gilmore Girls, A Family Matter" (show, episode) or just a
     * film's name. Titles can hold commas too, so take the split the catalogue recognises.
     */
    static void watchedCard(Context c, String pkg, String text) {
        try {
            if (match(c, pkg, text) != null) { watched(c, pkg, text, "", null); return; }
            for (int i = text.indexOf(", "); i > 0; i = text.indexOf(", ", i + 2)) {
                if (match(c, pkg, text.substring(0, i)) != null) { watched(c, pkg, text.substring(0, i), text.substring(i + 2), null); return; }
            }
        } catch (Exception e) {
            Log.w(TAG, "card " + text, e);
        }
        int i = text.indexOf(", ");
        watched(c, pkg, i > 0 ? text.substring(0, i) : text, i > 0 ? text.substring(i + 2) : "", null);
    }

    /**
     * JioHotstar's player names only the episode, but the path of its stream URL holds the show's and
     * the episode's content ids (/videos/hbo/episode6/1271680756/1271697860/…), and Hotstar's content
     * API names them. Only the ids are sent, never the URL: it carries the account's token.
     * {show, picture URL or null}, or null.
     */
    static String[] hotstarShow(String mediaUri) {
        if (mediaUri == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d{8,})(?=/)").matcher(String.valueOf(Uri.parse(mediaUri).getPath()));
        StringBuilder ids = new StringBuilder();
        while (m.find()) ids.append(ids.length() == 0 ? "" : ",").append(m.group(1));
        if (ids.length() == 0) return null;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://api.hotstar.com/o/v1/multi/get/content?ids=" + ids).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(12000);
            h.setRequestProperty("x-country-code", "IN");
            h.setRequestProperty("x-platform-code", "PCTV");
            JSONObject map;
            try (InputStream in = h.getInputStream()) {
                map = new JSONObject(readAll(in)).getJSONObject("body").getJSONObject("results").getJSONObject("map");
            } finally {
                h.disconnect();
            }
            for (java.util.Iterator<String> k = map.keys(); k.hasNext(); ) {
                JSONObject ep = map.getJSONObject(k.next());
                String show = ep.optString("showName");
                if (show.isEmpty()) continue;
                JSONObject s = map.optJSONObject(ep.optString("showContentId"));
                JSONObject img = (s != null ? s : ep).optJSONObject("images");
                String h16x9 = img == null ? "" : img.optString("h");
                return new String[]{show, h16x9.isEmpty() ? null : "https://img1.hotstarext.com/image/upload/f_auto,t_web_hs_2x/" + h16x9};
            }
        } catch (Exception e) {
            Log.w(TAG, "hotstar show: " + e);
        }
        return null;
    }

    /** History, newest first, one entry per title; {@code pkg} is the app it was watched or opened in. */
    static synchronized void remember(Context c, JSONObject title, String pkg) throws JSONException {
        title.put("watchedPkg", pkg).put("at", System.currentTimeMillis());
        JSONArray old = recentRaw(c), next = new JSONArray().put(title);
        for (int i = 0; i < old.length() && next.length() < 20; i++) {
            if (!old.getJSONObject(i).optString("id").equals(title.optString("id"))) next.put(old.get(i));
        }
        prefs(c).edit().putString("recent", next.toString()).apply();
    }

    /** Replace the stored entry with {@code t}'s id by {@code t}, keeping its place. */
    private static synchronized void patch(Context c, JSONObject t) {
        try {
            JSONArray r = recentRaw(c);
            for (int i = 0; i < r.length(); i++) {
                if (r.getJSONObject(i).optString("id").equals(t.optString("id"))) { r.put(i, t); break; }
            }
            prefs(c).edit().putString("recent", r.toString()).apply();
        } catch (JSONException ignored) { }
    }

    /** Take a title off the history. */
    static synchronized void forget(Context c, String id) {
        try {
            JSONArray old = recentRaw(c), next = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (!old.getJSONObject(i).optString("id").equals(id)) next.put(old.get(i));
            prefs(c).edit().putString("recent", next.toString()).apply();
        } catch (JSONException ignored) { }
    }

    /** YouTube isn't in the catalogue: use the video's own thumbnail as the art. */
    private static void youTubeArt(JSONObject entry, String query) {
        try {
            String[] y = youTubeHit(query);
            if (y != null) entry.put("poster", y[1]).put("thumb", y[1]).put("backdrop", y[1]).put("videoId", y[0]);
        } catch (Exception e) {
            Log.w(TAG, "youtube art " + query, e);
        }
    }

    private static final java.util.Set<String> artTried = new java.util.HashSet<>();

    static JSONArray recent(Context c) throws JSONException {
        JSONArray r = recentRaw(c);
        // YouTube entries saved before they got art: look it up once, in the background.
        List<JSONObject> bare = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject t = r.getJSONObject(i);
            if (YOUTUBE.equals(t.optString("watchedPkg")) && !t.has("poster") && artTried.add(t.optString("id"))) bare.add(t);
        }
        if (!bare.isEmpty()) new Thread(() -> {
            for (JSONObject t : bare) {
                String ep = t.optString("episode");
                youTubeArt(t, ep.isEmpty() ? t.optString("title") : ep + " " + t.optString("title"));
                if (t.has("poster")) patch(c, t);
            }
        }, "youtube-art").start();
        for (int i = 0; i < r.length(); i++) {
            JSONObject t = r.getJSONObject(i);
            JSONObject known = byId.get(t.optString("id"));
            // Entries saved before art was kept: borrow it from the catalogue when the title is there.
            if (!t.has("thumb") && known != null && known.has("thumb")) {
                t.put("thumb", known.getString("thumb")).put("backdrop", known.optString("backdrop"));
            }
            if (t.has("id") && known == null) byId.put(t.getString("id"), t);
        }
        return r;
    }

    private static JSONArray recentRaw(Context c) throws JSONException {
        return new JSONArray(prefs(c).getString("recent", "[]"));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("catalog", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String label(Context c, String pkg, String fallback) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return fallback;
        }
    }

    private static JSONObject post(JSONObject body) throws IOException, JSONException {
        HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(12000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        try (InputStream in = h.getResponseCode() < 400 ? h.getInputStream() : h.getErrorStream()) {
            return new JSONObject(readAll(in));
        } finally {
            h.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
