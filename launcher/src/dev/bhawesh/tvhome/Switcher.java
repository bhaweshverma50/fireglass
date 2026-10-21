package dev.bhawesh.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Apps in use, like a phone's recents: the apps opened in the last day, newest first. OK switches
 * to one, Down closes it (force-stop through KeyServer when it's running, else Android's
 * background kill), and "Close all" clears the lot. Recency comes from usage stats
 * (GET_USAGE_STATS, granted over adb at install).
 */
final class Switcher extends FrameLayout {

    private static final long WINDOW_MS = 24 * 3600 * 1000L;
    /** Closed here, and when: an app stays off the list until it's used again. */
    private static final Map<String, Long> closedAt = new HashMap<>();

    private final Consumer<MainActivity.App> open;
    private final Runnable done;
    private final LinearLayout row;
    private final TextView empty;
    private final List<View> cards = new ArrayList<>();
    private TextView closeAll;

    Switcher(Context c, Map<String, MainActivity.App> apps, Consumer<MainActivity.App> open, Runnable done) {
        super(c);
        this.open = open;
        this.done = done;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF05060A, 0xFC05060A, 0xFF05060A}));
        setClickable(true);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = Look.text(c, Look.serif(c), 40, Color.WHITE);
        title.setText("Apps in use");
        col.addView(title);
        TextView hint = Look.text(c, Look.sans(c, 500), 14, 0x99FFFFFF);
        hint.setText("OK switches to an app. Down closes it. Up for close all.");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.setMargins(0, dp(6), 0, 0);
        col.addView(hint, hp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cp.setMargins(dp(Look.PAD_X), dp(Look.PAD_TOP + 20), 0, 0);
        addView(col, cp);
        // Top right, one press up from any card.
        closeAll = closeAll();
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.END);
        ap.setMargins(0, dp(Look.PAD_TOP + 34), dp(Look.PAD_X), 0);
        addView(closeAll, ap);

        MainActivity.RowScroll scroll = new MainActivity.RowScroll(c, dp(Look.PAD_X));
        row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(Look.PAD_X), dp(30), dp(Look.PAD_X), dp(30));
        scroll.addView(row);
        addView(scroll, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));

        empty = Look.text(c, Look.sans(c, 500), 18, 0xB3FFFFFF);
        empty.setText("Nothing else is open.");
        empty.setVisibility(GONE);
        addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        List<MainActivity.App> recent = recent(c, apps);
        for (MainActivity.App a : recent) row.addView(card(a), Look.tileLp(c, 220, row.getChildCount() == 0));
        closeAll.setVisibility(recent.isEmpty() ? GONE : VISIBLE);
        wire();
        if (cards.isEmpty()) empty.setVisibility(VISIBLE);
        // Empty, it still needs focus so Back reaches it.
        setFocusable(cards.isEmpty());

        // Focus once attached (MainActivity.showSwitcher): asked for before then, it can stay behind.
        setAlpha(0f);
        animate().alpha(1f).setDuration(Look.ms(c, 220)).start();
        row.setTranslationY(dp(30));
        row.animate().translationY(0f).setDuration(Look.ms(c, 380)).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    /** First card, or the switcher itself when nothing is open. Called once it's on screen. */
    void takeFocus() {
        if (cards.isEmpty()) requestFocus();
        else cards.get(0).requestFocus();
    }

    /** Apps used in the last day, newest first, minus this launcher and ones closed since. */
    static List<MainActivity.App> recent(Context c, Map<String, MainActivity.App> apps) {
        List<MainActivity.App> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        Map<String, Long> last = new HashMap<>();
        for (UsageStats u : usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now)) {
            if (u.getLastTimeUsed() < now - WINDOW_MS) continue;
            Long prev = last.get(u.getPackageName());
            if (prev == null || u.getLastTimeUsed() > prev) last.put(u.getPackageName(), u.getLastTimeUsed());
        }
        List<Map.Entry<String, Long>> order = new ArrayList<>(last.entrySet());
        order.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> e : order) {
            MainActivity.App a = apps.get(e.getKey());
            Long closed = closedAt.get(e.getKey());
            if (a == null || (closed != null && e.getValue() <= closed)) continue;
            a.lastUsed = e.getValue();
            out.add(a);
        }
        return out;
    }

    private View card(MainActivity.App a) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        FrameLayout card = new MainActivity.AspectFrame(getContext());
        card.setFocusable(true);
        card.setTag(a);
        Look.roundClip(card, dp(16));
        card.setBackgroundColor(0xFF1C1C22);
        card.setOutlineSpotShadowColor(a.color);
        card.setOutlineAmbientShadowColor(a.color);
        ImageView img = new ImageView(getContext());
        img.setImageDrawable(a.art);
        img.setScaleType(a.isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View rim = new View(getContext());
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(dp(16));
        rg.setStroke(dp(2), 0xD9FFFFFF);
        rim.setBackground(rg);
        rim.setAlpha(0f);
        card.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView name = Look.text(getContext(), Look.sans(getContext(), 600), 16, Color.WHITE);
        name.setText(a.label);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.setMargins(dp(2), dp(16), 0, 0);
        box.addView(name, np);
        TextView when = Look.text(getContext(), Look.sans(getContext(), 500), 13, 0x8CFFFFFF);
        long ago = System.currentTimeMillis() - a.lastUsed;
        when.setText(ago < 60_000 ? "Used just now" : "Used " + DateUtils.getRelativeTimeSpanString(a.lastUsed,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().toLowerCase(java.util.Locale.ROOT));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.setMargins(dp(2), dp(3), 0, 0);
        box.addView(when, wp);

        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).translationZ(has ? dp(18) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator(2f))
                    .setDuration(Look.ms(getContext(), has ? 360 : 220)).start();
            rim.animate().alpha(has ? 1f : 0f).setDuration(Look.ms(getContext(), 180)).start();
        });
        card.setOnClickListener(v -> open.accept(a));
        card.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            closeCard(box, a);
            return true;
        });
        cards.add(card);
        return box;
    }

    private TextView closeAll() {
        TextView b = Look.text(getContext(), Look.sans(getContext(), 600), 15, Color.WHITE);
        b.setText("Close all apps");
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setFocusable(true);
        b.setId(generateViewId());
        Look.Glass glass = new Look.Glass(dp(22), 1f);
        GradientDrawable solid = new GradientDrawable();
        solid.setCornerRadius(dp(22));
        solid.setColor(0xF2FFFFFF);
        b.setBackground(glass);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? solid : glass);
            b.setTextColor(has ? 0xFF16161C : Color.WHITE);
        });
        b.setOnClickListener(v -> {
            for (View c : new ArrayList<>(cards)) close(getContext(), ((MainActivity.App) c.getTag()).pkg);
            done.run();
        });
        b.setNextFocusLeftId(b.getId());
        b.setNextFocusRightId(b.getId());
        b.setNextFocusUpId(b.getId());
        return b;
    }

    /** Slide the card up and away, close the app, close the gap. */
    private void closeCard(View box, MainActivity.App a) {
        int i = cards.indexOf(box instanceof LinearLayout ? ((LinearLayout) box).getChildAt(0) : box);
        close(getContext(), a.pkg);
        box.animate().translationY(-dp(140)).alpha(0f).setDuration(Look.ms(getContext(), 260))
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                    cards.remove(i);
                    row.removeView(box);
                    if (cards.isEmpty()) { done.run(); return; }
                    wire();
                    cards.get(Math.min(i, cards.size() - 1)).requestFocus();
                }).start();
    }

    /** Force-stop when the shell key sender is up (truly closes it), else Android's background kill. */
    static void close(Context c, String pkg) {
        closedAt.put(pkg, System.currentTimeMillis());
        new Thread(() -> {
            try {
                if (Keys.send(c, "stop " + pkg).startsWith("ok")) return;
            } catch (Exception ignored) { }
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        }, "close-app").start();
    }

    private void wire() {
        for (int k = 0; k < cards.size(); k++) {
            View v = cards.get(k);
            if (v.getId() == NO_ID) v.setId(generateViewId());
        }
        for (int k = 0; k < cards.size(); k++) {
            View v = cards.get(k);
            v.setNextFocusLeftId(cards.get(Math.max(0, k - 1)).getId());
            v.setNextFocusRightId(cards.get(Math.min(cards.size() - 1, k + 1)).getId());
            v.setNextFocusUpId(closeAll.getId());
            v.setNextFocusDownId(v.getId());
        }
        if (!cards.isEmpty()) closeAll.setNextFocusDownId(cards.get(0).getId());
    }

    void dismiss() {
        animate().alpha(0f).setDuration(Look.ms(getContext(), 180)).withEndAction(() -> {
            if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(this);
        }).start();
    }

    private int dp(float v) { return Look.dp(getContext(), v); }
}
