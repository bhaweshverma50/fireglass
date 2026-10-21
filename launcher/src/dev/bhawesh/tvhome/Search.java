package dev.bhawesh.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search on the TV: a field the Fire TV keyboard types into, then what matches on the apps
 * installed here, as posters, and YouTube's own results as videos. Alexa voice searches land
 * here too: HomeService reads the query off Amazon's results page and opens this with it.
 */
public class Search extends Activity {
    static final String QUERY = "q", FROM_VOICE = "voice";
    private static final int POSTER_W = 112, PER_ROW = 6, VIDEO_W = 190, VIDEOS_PER_ROW = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Runnable run = this::search;
    private SkyView sky;
    private EditText field;
    private TextView status;
    private LinearLayout results, apps, tube; // apps, then YouTube, in results
    private boolean appsDone;
    private ScrollView scroll;
    private int seq;
    private boolean focusResults; // a voice query: go straight to the first result
    private boolean fromVoice;    // opened over Amazon's results page: Back goes home, not back to it

    /** "search for mirzapur" -> "mirzapur": the words Alexa heard, less the asking. */
    static String spokenQuery(String said) {
        return said.trim().replaceFirst("(?i)^(alexa[ ,]+)?(please\\s+)?(search(\\s+for)?|find(\\s+me)?|show(\\s+me)?|look\\s+(for|up))\\s+", "").trim();
    }

    static void open(Context c, String query) {
        c.startActivity(new Intent(c, Search.class).putExtra(QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sky = new SkyView(this);
        sky.setLook(HomeSettings.get(this, HomeSettings.LOOK));
        sky.setAccent(MainActivity.accent, 0.4f, false);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0xCC000000, 0xE6000000}));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Full width, margins inside, so a lifted poster at either edge isn't clipped by the scroller.
        col.setPadding(0, dp(Look.PAD_TOP), 0, 0);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(56));
        fp.setMargins(dp(Look.PAD_X), 0, dp(Look.PAD_X), 0);
        col.addView(buildField(), fp);
        status = Look.text(this, Look.sans(this, 500), 14, 0x99FFFFFF);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(dp(Look.PAD_X + 4), dp(18), 0, dp(4));
        col.addView(status, sp);
        scroll = new MainActivity.HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(48));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        results.setPadding(dp(Look.PAD_X), 0, dp(Look.PAD_X), 0);
        results.setClipToPadding(false);
        apps = new LinearLayout(this);
        tube = new LinearLayout(this);
        for (LinearLayout box : new LinearLayout[]{apps, tube}) {
            box.setOrientation(LinearLayout.VERTICAL);
            box.setClipChildren(false);
            box.setClipToPadding(false);
            results.addView(box);
        }
        scroll.addView(results);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        take(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        take(i);
    }

    private void take(Intent i) {
        fromVoice = i.getBooleanExtra(FROM_VOICE, false);
        String q = i.getStringExtra(QUERY);
        if (q != null && !q.trim().isEmpty()) {
            focusResults = true;
            field.setText(q.trim());
            field.setSelection(field.length());
            handler.removeCallbacks(run);
            search();
        } else {
            status.setText("Shows, films and people, on the apps on this TV."); status.setVisibility(View.VISIBLE);
            field.requestFocus();
            handler.postDelayed(this::showKeyboard, 250);
        }
    }

    private View buildField() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(20), 0, dp(20), 0);
        pill.setBackground(new Look.Glass(dp(28), 0.95f));
        ImageView icon = new ImageView(this);
        Look.Icon glass = new Look.Icon(Look.Icon.SEARCH, dp(20));
        glass.setColor(0xCCFFFFFF);
        icon.setImageDrawable(glass);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        field = new EditText(this);
        field.setTypeface(Look.serif(this));
        field.setTextSize(24);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0x66FFFFFF);
        field.setHint("Search shows, films, YouTube");
        field.setBackground(null);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(dp(14), 0, 0, 0);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(run);
                handler.postDelayed(run, 650);
            }
        });
        field.setOnEditorActionListener((v, action, e) -> {
            handler.removeCallbacks(run);
            focusResults = true;
            hideKeyboard();
            search();
            return true;
        });
        // Down from the field goes to the results; the keyboard closes on its own.
        field.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_DOWN || e.getAction() != KeyEvent.ACTION_DOWN) return false;
            hideKeyboard();
            View first = firstResult();
            if (first != null) first.requestFocus();
            return first != null;
        });
        pill.addView(field, new LinearLayout.LayoutParams(0, -1, 1f));
        return pill;
    }

    private void search() {
        String q = field.getText().toString().trim();
        int my = ++seq;
        apps.removeAllViews();
        tube.removeAllViews();
        appsDone = false;
        if (q.isEmpty()) {
            status.setText("Shows, films and people, on the apps on this TV."); status.setVisibility(View.VISIBLE);
            return;
        }
        status.setText("Searching…");
        status.setVisibility(View.VISIBLE);
        // The catalogue and YouTube at once; each fills its own part as it answers.
        io.execute(() -> {
            try {
                JSONArray found = Catalog.search(this, q, 24);
                List<JSONObject> playable = new ArrayList<>();
                for (int i = 0; i < found.length(); i++) {
                    JSONObject t = found.getJSONObject(i);
                    if (t.optJSONArray("offers") != null && t.getJSONArray("offers").length() > 0) playable.add(t);
                }
                runOnUiThread(() -> { if (my == seq && !isDestroyed()) showApps(q, playable); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (my != seq || isDestroyed()) return;
                    appsDone = true;
                    status.setText("Can't reach the catalogue right now. Is the TV online?");
                    status.setVisibility(View.VISIBLE);
                    maybeFocus();
                });
            }
        });
        io.execute(() -> {
            try {
                JSONArray videos = Catalog.youTubeSearch(q, 8);
                runOnUiThread(() -> { if (my == seq && !isDestroyed()) showTube(videos); });
            } catch (Exception ignored) { }
        });
    }

    private void showApps(String q, List<JSONObject> found) {
        appsDone = true;
        // Section headings live in the results, so they scroll away with their section.
        status.setText(found.isEmpty() ? "Nothing on your apps for “" + q + "”." : "");
        status.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
        if (!found.isEmpty()) apps.addView(heading("On your apps", 0));
        LinearLayout row = null;
        for (int i = 0; i < found.size(); i++) {
            if (i % PER_ROW == 0) row = newRow(apps);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(POSTER_W), -2);
            lp.setMargins(i % PER_ROW == 0 ? 0 : dp(22), 0, 0, 0);
            row.addView(card(found.get(i)), lp);
        }
        maybeFocus();
    }

    private void showTube(JSONArray videos) {
        if (videos.length() == 0) return;
        tube.addView(heading("On YouTube", apps.getChildCount() == 0 ? 0 : dp(10)));
        LinearLayout row = null;
        for (int i = 0; i < videos.length(); i++) {
            if (i % VIDEOS_PER_ROW == 0) row = newRow(tube);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(VIDEO_W), -2);
            lp.setMargins(i % VIDEOS_PER_ROW == 0 ? 0 : dp(22), 0, 0, 0);
            row.addView(videoCard(videos.optJSONObject(i)), lp);
        }
        maybeFocus();
    }

    private TextView heading(String text, int top) {
        TextView h = Look.text(this, Look.sans(this, 500), 14, 0x99FFFFFF);
        h.setText(text);
        h.setLayoutParams(lp(-2, -2, top + dp(18)));
        h.setPadding(dp(4), 0, 0, 0);
        return h;
    }

    private LinearLayout newRow(LinearLayout box) {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, dp(10), 0, dp(14));
        box.addView(row);
        return row;
    }

    /** A voice search goes straight to the first result: on the apps if any, else on YouTube. */
    private void maybeFocus() {
        if (!focusResults) return;
        View f = first(apps);
        if (f == null && appsDone) f = first(tube);
        if (f == null) return;
        focusResults = false;
        hideKeyboard();
        f.requestFocus();
    }

    private View firstResult() {
        View f = first(apps);
        return f != null ? f : first(tube);
    }

    private static View first(LinearLayout box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View r = box.getChildAt(i);
            if (r instanceof LinearLayout && ((LinearLayout) r).getChildCount() > 0 && ((LinearLayout) r).getChildAt(0).isFocusable()) {
                return ((LinearLayout) r).getChildAt(0);
            }
        }
        return null;
    }

    /** A poster with its name and where it's on underneath; focus lifts the poster. */
    private View card(JSONObject t) {
        JSONArray offers = t.optJSONArray("offers");
        JSONObject offer = offers.optJSONObject(0);
        StringBuilder meta = new StringBuilder(offer.optString("label"));
        if (offers.length() > 1) meta.append(" +").append(offers.length() - 1);
        if (t.optInt("year") > 0) meta.append(" · ").append(t.optInt("year"));
        View card = tile(t.optString("poster", t.optString("thumb", null)), dp(POSTER_W) * 3 / 2, null,
                t.optString("title"), 1, meta.toString());
        card.setOnClickListener(v -> {
            if (Catalog.open(this, offer)) io.execute(() -> {
                try { Catalog.remember(this, new JSONObject(t.toString()), offer.optString("pkg")); } catch (Exception ignored) { }
            });
        });
        return card;
    }

    /** A YouTube video: its 16:9 thumbnail with the length on it, title and channel; plays in YouTube. */
    private View videoCard(JSONObject v) {
        View card = tile(v.optString("thumb"), dp(VIDEO_W) * 9 / 16, v.optString("length"),
                v.optString("title"), 2, v.optString("channel"));
        card.setOnClickListener(x -> Catalog.playYouTubeVideo(this, v.optString("id")));
        return card;
    }

    /** Picture (with an optional badge in its corner) over a name and a dim line; focus lifts the picture. */
    private View tile(String url, int pictureH, String badge, String title, int titleLines, String line) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setFocusable(true);
        card.setClickable(true);

        FrameLayout pic = new FrameLayout(this);
        pic.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A2A33, 0xFF121217}));
        Look.roundClip(pic, dp(Look.TILE_RADIUS));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (badge != null && !badge.isEmpty()) {
            TextView b = Look.text(this, Look.sans(this, 600), 10, Color.WHITE);
            b.setText(badge);
            b.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(0xCC000000);
            b.setBackground(bg);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
            bp.setMargins(0, 0, dp(6), dp(6));
            pic.addView(b, bp);
        }
        View rim = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(dp(Look.TILE_RADIUS));
        rg.setStroke(dp(2), 0xE6FFFFFF);
        rim.setBackground(rg);
        rim.setAlpha(0f);
        pic.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        card.addView(pic, new LinearLayout.LayoutParams(-1, pictureH));

        TextView name = Look.text(this, Look.sans(this, 600), 13, Color.WHITE);
        name.setText(title);
        name.setMaxLines(titleLines);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name, lp(-1, -2, dp(10)));
        TextView sub = Look.text(this, Look.sans(this, 500), 11, 0x8CFFFFFF);
        sub.setText(line);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(sub, lp(-1, -2, dp(2)));

        card.setOnFocusChangeListener((v, has) -> {
            pic.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator(2f))
                    .setDuration(has ? 380 : 220).start();
            rim.animate().alpha(has ? 1f : 0f).setDuration(180).start();
            name.animate().translationY(has ? dp(5) : 0).setDuration(220).start();
            sub.animate().translationY(has ? dp(5) : 0).setDuration(220).start();
            if (has) scrollTo(card);
        });
        io.execute(() -> {
            Bitmap bm = Net.image(this, url, 260);
            if (bm != null) runOnUiThread(() -> img.setImageBitmap(bm));
        });
        return card;
    }

    private void scrollTo(View v) {
        int y = 0;
        for (View p = v; p != null && p != results; p = (View) p.getParent()) y += p.getTop();
        scroll.smoothScrollTo(0, Math.max(0, y - dp(24)));
    }

    private void showKeyboard() {
        getSystemService(InputMethodManager.class).showSoftInput(field, 0); // explicit: Fire TV's keyboard ignores implicit requests
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    @Override protected void onResume() {
        super.onResume();
        sky.setRunning(true);
        HomeService.hideMask(); // the cover a voice search came in under
    }

    @Override public void onBackPressed() {
        if (fromVoice) startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
    @Override protected void onPause() { super.onPause(); sky.setRunning(false); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }

    private static LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, top, 0, 0);
        return p;
    }

    private int dp(float v) { return Look.dp(this, v); }
}
