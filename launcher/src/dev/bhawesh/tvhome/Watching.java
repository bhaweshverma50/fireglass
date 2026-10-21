package dev.bhawesh.tvhome;

import android.app.Notification;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watch history across apps: what each app reports as "now playing" (its media session, the same
 * thing Alexa reads). Only a notification listener may read other apps' sessions, so this is one;
 * the only notifications it reads are apps' continue-watching cards for Amazon's home (Netflix's
 * player names nothing, but its card does). Enabled over adb at install (build-and-install.sh).
 * Something counts as watched after a minute of playing; Catalog.watched() records it.
 */
public class Watching extends NotificationListenerService {
    private static final String TAG = "TvHome";
    private static final long WATCHED_AFTER_MS = 60_000;
    // "S1 E9 Maiden Mother Crone", "S1:E9 …", "Season 1 Episode 9 …"
    private static final Pattern EPISODE = Pattern.compile("^\\s*(S(eason)?\\s*\\d+\\s*[:,.]?\\s*E(p(isode)?)?\\s*\\d+)\\b.*",
            Pattern.CASE_INSENSITIVE);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    // The item each session's minute is running for.
    private final Map<MediaController, String> timing = new HashMap<>();
    private MediaSessionManager msm;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, Watching.class);
        msm.addOnActiveSessionsChangedListener(changed, me);
        List<MediaController> now = msm.getActiveSessions(me);
        Log.i(TAG, "watch history listening; sessions: " + now.size());
        track(now);
        StatusBarNotification[] posted = getActiveNotifications();
        if (posted != null) for (StatusBarNotification n : posted) onNotificationPosted(n);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        String group = n.getGroup();
        if (!Notification.CATEGORY_RECOMMENDATION.equals(n.category) || group == null
                || !group.toLowerCase(java.util.Locale.ROOT).contains("continue")) return;
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null || text.toString().trim().isEmpty()) return;
        String pkg = sbn.getPackageName(), what = text.toString().trim();
        // Apps repost the same card on every launch; only a new item counts as watched.
        SharedPreferences seen = getSharedPreferences("watching", MODE_PRIVATE);
        if (what.equals(seen.getString(pkg, null))) return;
        seen.edit().putString(pkg, what).apply();
        Log.i(TAG, "continue watching card " + pkg + ": " + what);
        new Thread(() -> Catalog.watchedCard(this, pkg, what), "watched").start();
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(changed);
        untrackAll();
    }

    private void track(List<MediaController> sessions) {
        untrackAll();
        if (sessions == null) return;
        for (MediaController mc : sessions) {
            if (mc.getPackageName().startsWith("com.amazon.alexa") || mc.getPackageName().equals("com.amazon.vizzini")) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState s) { consider(mc); }
                @Override public void onMetadataChanged(MediaMetadata m) { consider(mc); }
            };
            mc.registerCallback(cb, handler);
            watched.put(mc, cb);
            consider(mc);
        }
        Lights.playing(this, anyPlaying()); // a closed app's session is just gone: that's stopped too
    }

    /** For the lights' dim-while-playing. */
    private boolean anyPlaying() {
        for (MediaController c : watched.keySet()) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return true;
        }
        return false;
    }

    private void untrackAll() {
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) e.getKey().unregisterCallback(e.getValue());
        watched.clear();
        timing.clear();
        handler.removeCallbacksAndMessages(null);
    }

    /** While a session plays the same item, count it as watched once it has played a minute. */
    private void consider(MediaController mc) {
        Lights.playing(this, anyPlaying());
        PlaybackState s = mc.getPlaybackState();
        MediaMetadata m = mc.getMetadata();
        String[] item = s == null || s.getState() != PlaybackState.STATE_PLAYING || m == null ? null : item(m);
        String key = item == null ? null : item[0] + "\n" + item[1];
        // Apps report progress every few seconds (JioHotstar): the same item keeps its running minute.
        if (key != null && key.equals(timing.get(mc))) return;
        handler.removeCallbacksAndMessages(mc);
        timing.remove(mc);
        if (item == null) return;
        // What each app reports varies; logged so the parsing above can be tuned per app.
        Log.i(TAG, "playing " + mc.getPackageName() + ": " + describe(m));
        timing.put(mc, key);
        handler.postAtTime(() -> {
            timing.remove(mc);
            PlaybackState now = mc.getPlaybackState();
            MediaMetadata nm = mc.getMetadata();
            String[] still = nm == null ? null : item(nm);
            if (now == null || now.getState() != PlaybackState.STATE_PLAYING || still == null
                    || !still[0].equals(item[0]) || !still[1].equals(item[1])) return;
            String pkg = mc.getPackageName();
            String uri = item[0].isEmpty() && Catalog.HOTSTAR.equals(pkg) ? nm.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) : null;
            new Thread(() -> {
                String[] hs = Catalog.hotstarShow(uri);
                Catalog.watched(this, pkg, hs != null ? hs[0] : item[0], item[1], hs != null ? hs[1] : null);
            }, "watched").start();
        }, mc, android.os.SystemClock.uptimeMillis() + WATCHED_AFTER_MS);
    }

    /**
     * {show or movie, episode or ""} from what the app reports, show "" when only the episode is
     * named. Apps put the episode in the title and the show in the subtitle/album/artist, or the
     * show alone in the title.
     */
    static String[] item(MediaMetadata m) {
        String title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String show = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (title.isEmpty()) return show.isEmpty() ? null : new String[]{show, ""};
        Matcher e = EPISODE.matcher(title);
        boolean episodic = e.matches() || (!show.isEmpty() && !show.equalsIgnoreCase(title));
        if (episodic && !show.isEmpty()) return new String[]{show, title};
        if (e.matches()) return new String[]{"", title}; // an episode with no show name (JioHotstar)
        return new String[]{title, ""};
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }

    private static String describe(MediaMetadata m) {
        StringBuilder sb = new StringBuilder();
        for (String k : m.keySet()) {
            CharSequence v = m.getText(k);
            // Skip links: JioHotstar's MEDIA_URI is a signed stream URL with the account's token in it.
            String name = k.replace("android.media.metadata.", "");
            android.graphics.Bitmap bm = m.getBitmap(k);
            if (bm != null) sb.append(name).append("=<").append(bm.getWidth()).append('x').append(bm.getHeight()).append(">; ");
            else if (v != null && !k.endsWith("_URI")) sb.append(name).append('=').append(v).append("; ");
            else if (v == null) sb.append(name).append("; ");
        }
        return sb.toString();
    }
}
