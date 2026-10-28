package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private ValueAnimator scrollGlide;
    private boolean haShown, keepShown;
    /** Last glow colour, so the Home-key cover can light its dock the same way (same process). */
    static volatile int glowNow = 0xFF3A3A3A;
    /** The photo on screen right now, so the cover borrows it instead of decoding (and showing) an older one. */
    static volatile Bitmap currentWall;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    // ---- Home Assistant snapshot panel (JSON written by an HA automation, polled over the LAN)
    static volatile long suppressRedirectUntil;
    private static final int KEEP_W_DP = 156;   // slim strip: the row reads as secondary to the app title
    private static final int NEW_W_DP = KEEP_W_DP * 2 + 10;   // the new-media banner spans two slots
    private static final long NEW_EVERY_MS = 7000;
    private LinearLayout keepRow;
    private final List<View> keepCards = new ArrayList<>();
    // Rotating "new on Plex" tile: the first card in the keep row. Its layers are the individual
    // titles, crossfaded in place, so the focus target never moves while the artwork changes.
    private View newTile;
    private final List<View> newLayers = new ArrayList<>();
    private final List<JSONObject> newItems = new ArrayList<>();
    private TextView newCaption;
    private int newCur;
    private String newSig = "";
    private final Runnable newTick = this::advanceNew;
    private LinearLayout haPanel;
    private TextView haBig, haSub, haL1, haL2, haL3, haL4, haL5;
    private FrameLayout settingsPage;
    private TextView settingsPill;
    private View timeTint;
    private static final long IDLE_MS = 3 * 60 * 1000;
    private final Runnable idle = () -> {
        if (hasWindowFocus() && inputsPopup == null) startActivity(new Intent(this, ClockSaverActivity.class));
    };
    private void kickIdle() { handler.removeCallbacks(idle); handler.postDelayed(idle, IDLE_MS); }
    private String familyName, deviceName;
    private int pollN;
    private final Runnable haPoll = new Runnable() {
        @Override public void run() { fetchHa(); if (pollN++ % 20 == 0) { fetchKeep(); fetchNew(); } handler.postDelayed(this, 15000); }
    };

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        familyName = getString(R.string.family_name);
        deviceName = getString(R.string.device_name);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        HomeKeyService.coverGone();
        super.onResume();
        getSharedPreferences("tvhome", 0).edit().putBoolean("foreground", true).apply();   // read by BootReceiver
        kickIdle();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        suppressRedirectUntil = 0;
        handler.postDelayed(this::ensureHomeService, 4000);
        handler.removeCallbacks(haPoll);
        handler.post(haPoll);
        startNewRotation();
        if (pendingFocusPkg != null) {   // keep the cursor on an app we just moved
            for (View c : dockCards) if (((App) c.getTag()).pkg.equals(pendingFocusPkg)) lastFocusedId = c.getId();
            for (View c : gridCards) if (((App) c.getTag()).pkg.equals(pendingFocusPkg)) lastFocusedId = c.getId();
            pendingFocusPkg = null;
        }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        focusNow(last);
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences("tvhome", 0).edit().putBoolean("foreground", false).apply();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(haPoll);
        handler.removeCallbacks(newTick);
        handler.removeCallbacks(idle);
        unregisterReceiver(tick);
        if (scrollGlide != null) scrollGlide.cancel();
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        glideTo(0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        timeTint = new View(this);
        root.addView(timeTint, new FrameLayout.LayoutParams(-1, -1));
        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));
        hero.addView(buildHaPanel(), lp(dp(360), -2, Gravity.END | Gravity.TOP, 0, dp(110), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));
        bottom.addView(buildKeepRow(), 0);

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(0, 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        dockRow.setElevation(dp(12));   // lifts the tray off the photo instead of pasting it on
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }




    /** Amazon's own network page; falls back to the generic Wi-Fi intent on other builds. */
    private void openWifiSettings() {
        Intent i = new Intent().setComponent(new ComponentName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.network.NetworkActivity"));
        if (!launch(i) && !launch(new Intent(Settings.ACTION_WIFI_SETTINGS))) openFullSettings();
    }

    /** Amazon's own full-screen Settings grid; falls back to the generic settings intent on other Fire OS builds. */
    private void openFullSettings() {
        Intent i = new Intent().setComponent(new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"));
        if (!launch(i)) launch(new Intent(Settings.ACTION_SETTINGS));
    }

    // ---------------------------------------------------------------- Home key service self-heal

    private void ensureHomeService() {
        if (HomeKeyService.running) return;
        HomeKeyService.rearm(this);
    }

    // ---------------------------------------------------------------- keep watching

    private View buildKeepRow() {
        keepRow = new LinearLayout(this);
        keepRow.setOrientation(LinearLayout.HORIZONTAL);
        keepRow.setClipChildren(false);
        keepRow.setClipToPadding(false);
        keepRow.setVisibility(View.GONE);
        keepRow.setPadding(0, 0, 0, dp(14));
        return keepRow;
    }

    private void fetchKeep() {
        final String url = getString(R.string.keep_json_url);
        if (url.isEmpty()) return;
        new Thread(() -> {
            final String body = Net.text(url, 4000, 4000, Net.MAX_JSON_BYTES);
            if (body == null) return;
            try {
                final org.json.JSONArray items = new JSONObject(body).optJSONArray("items");
                final List<Bitmap> imgs = new ArrayList<>();
                final List<Bitmap> logos = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    String img = items.getJSONObject(i).optString("image", "");
                    imgs.add(img.isEmpty() ? null : Net.image(img, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
                    String logo = items.getJSONObject(i).optString("logo", "");
                    logos.add(logo.isEmpty() ? null : Net.image(logo, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
                }
                runOnUiThread(() -> showKeep(items, imgs, logos));
            } catch (Exception ignored) { }
        }).start();
    }

    private void showKeep(org.json.JSONArray items, List<Bitmap> imgs, List<Bitmap> logos) {
        boolean hadFocus = newTile != null && newTile.isFocused();
        keepRow.removeAllViews();
        keepCards.clear();
        // The row does not scroll, so extra cards would run off the edge under the weather panel.
        // The new-media tile takes the first slot when it is enabled, leaving room for four cards.
        if (newTile != null) {
            keepCards.add(newTile);
            keepRow.addView(newTile, llp(dp(NEW_W_DP), -2, 0, 0, dp(10), 0));
            if (hadFocus) newTile.requestFocus();
        }
        // A two-slot banner plus one resume card is what fits beside the weather panel.
        int shown = items == null ? 0 : Math.min(items.length(), newTile == null ? 5 : 1);
        for (int i = 0; i < shown; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) {
                View kc = keepCard(it, imgs.get(i), logos.get(i));
                keepCards.add(kc);
                keepRow.addView(kc, llp(dp(KEEP_W_DP), -2, 0, 0, dp(10), 0));
            }
        }
        if (keepRow.getChildCount() == 0) { keepRow.setVisibility(View.GONE); return; }
        if (keepRow.getVisibility() != View.VISIBLE) {
            keepRow.setVisibility(View.VISIBLE);
            if (!keepShown) {
                keepShown = true;
                keepRow.setAlpha(0f);
                keepRow.setTranslationY(dp(10));
                keepRow.animate().alpha(1f).translationY(0)
                        .setInterpolator(new DecelerateInterpolator(2f)).setDuration(260).start();
            }
        }
        rewireKeep();
        startNewRotation();   // the rebuild detaches and re-adds the tile; make sure its timer survived
    }

    private View keepCard(JSONObject it, Bitmap bm, Bitmap logo) {
        final String pkg = it.optString("pkg", "");
        final String uri = it.optString("uri", "");
        final int accent = pkg.contains("netflix") ? 0xFFE50914 : pkg.contains("tvplayer") ? 0xFF2E9BFF : 0xFFE5A00D;
        // Still on top, caption underneath. The old 62dp card had to stack the label, the show logo and
        // the episode line inside the artwork, where they overlapped; the caption needs its own room.
        final int cardW = dp(KEEP_W_DP);   // the bar and the logo are measured from this, so they cannot drift
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        // The whole tile is the focus target, so the caption below is part of it and the D-pad ids the
        // rewireKeep() table hands out (this view) still resolve.
        box.setId(View.generateViewId());
        box.setFocusable(true);
        box.setClickable(true);

        FrameLayout card = new AspectFrame(this);   // 16:9: the stills are 16:9, and a 3:1 crop decapitates them
        card.setFocusable(false);
        card.setClickable(false);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
        });
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{darken(accent, 0.55f), 0xFF14161C});
        card.setBackground(base);
        if (bm != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bm);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        View shade = new View(this);   // the logo and the progress bar sit on this, not on bare artwork
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xC9000000, 0x66000000, 0x00000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        double prog = it.optDouble("progress", 0);
        if (prog > 0) {   // inset from the corners so a straight bar does not fight the rounding
            FrameLayout track = new FrameLayout(this);
            View groove = new View(this);
            GradientDrawable gg = new GradientDrawable();
            gg.setCornerRadius(dp(2f));
            gg.setColor(0x4DFFFFFF);
            groove.setBackground(gg);
            track.addView(groove, new FrameLayout.LayoutParams(-1, dp(3)));
            View bar = new View(this);
            GradientDrawable fb = new GradientDrawable();
            fb.setCornerRadius(dp(2f));
            fb.setColor(accent);
            bar.setBackground(fb);
            track.addView(bar, new FrameLayout.LayoutParams(
                    Math.max(dp(6), (int) ((cardW - dp(18)) * Math.min(1.0, prog))), dp(3)));
            card.addView(track, lp(-1, dp(3), Gravity.BOTTOM, dp(9), 0, dp(9), dp(7)));
        }

        // Inside the card: the show's own logo. Plex and Netflix hand one over as a transparent PNG,
        // and it is the whole point of the artwork - our text only stands in when there is none.
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        capLp.setMargins(dp(10), 0, dp(10), dp(11));   // clears the progress bar
        if (logo != null) {
            ImageView lv = new ImageView(this);
            lv.setImageBitmap(logo);
            lv.setAdjustViewBounds(true);
            lv.setScaleType(ImageView.ScaleType.FIT_START);
            lv.setMaxHeight(dp(19));
            lv.setMaxWidth(dp(120));
            lv.setContentDescription(it.optString("title", ""));
            card.addView(lv, capLp);
        } else {
            TextView cap = text(11.5f, "sans-serif-medium", Color.WHITE);
            cap.setSingleLine(true);
            cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cap.setText(it.optString("title", ""));
            card.addView(cap, capLp);
        }

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        SpannableStringBuilder meta = new SpannableStringBuilder();
        String appName = it.optString("app", "").toUpperCase(Locale.getDefault());
        if (!appName.isEmpty()) coloured(meta, appName, accent);
        String subText = it.optString("sub", "");
        if (!subText.isEmpty()) {
            if (meta.length() > 0) coloured(meta, "   \u00B7   ", 0x99FFFFFF);
            coloured(meta, subText, 0xD9FFFFFF);
        }
        TextView sub = text(9f, "sans-serif", 0xD9FFFFFF);
        setSpan(sub, meta);   // collapses to GONE when there is neither an app nor an episode line
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);   // this line does sit on the photo; a wide blur smears 9sp type
        box.addView(sub, llp(-1, -2, 0, dp(6), 0, 0));

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(12));
        ring.setStroke(dp(2), 0x00FFFFFF);
        card.setForeground(ring);
        card.setElevation(dp(4));
        box.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(dp(2), has ? 0xFFFFFFFF : 0x00FFFFFF);
            card.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                    .translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.5f) : new DecelerateInterpolator())
                    .setDuration(has ? 240 : 180).start();
        });
        box.setOnClickListener(v -> {
            pressDip(card, 1.05f);
            if (!uri.isEmpty()) {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
                if (!pkg.isEmpty()) i.setPackage(pkg);
                if (launch(i)) return;
            }
            if (!pkg.isEmpty() && openPackage(pkg)) return;
            pressUndo(card, 1.05f);
        });
        return box;
    }

    // ---------------------------------------------------------------- new on Plex (rotating tile)

    private void fetchNew() {
        final String url = getString(R.string.new_json_url);
        if (url.isEmpty()) return;
        new Thread(() -> {
            final String body = Net.text(url, 4000, 4000, Net.MAX_JSON_BYTES);
            if (body == null) return;
            try {
                // A live card carries the end of its programme: the feed is written every ten minutes, so
                // drop anything that has finished rather than claim it is on now.
                final org.json.JSONArray all = new JSONObject(body).optJSONArray("items");
                final org.json.JSONArray items = new org.json.JSONArray();
                final long now = System.currentTimeMillis() / 1000L;
                if (all != null) for (int i = 0; i < all.length(); i++) {
                    JSONObject it = all.optJSONObject(i);
                    if (it == null) continue;
                    long until = it.optLong("until", 0);
                    if (until > 0 && until <= now) continue;
                    items.put(it);
                }
                if (items.length() == 0) { runOnUiThread(this::hideNewTile); return; }
                StringBuilder sig = new StringBuilder();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null) sig.append(it.optString("title", "")).append('|')
                            .append(it.optString("image", "")).append('|')
                            .append(it.optLong("until", 0)).append('|');
                }
                final String sigNow = sig.toString();
                // Same list as the one already on screen: leave it alone. Rebuilding would restart the
                // crossfade every poll and re-download eight stills every five minutes.
                if (newTile != null && sigNow.equals(newSig)) return;
                final List<Bitmap> imgs = new ArrayList<>(), logos = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    String img = it.optString("image", "");
                    imgs.add(img.isEmpty() ? null : Net.image(img, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
                    String logo = it.optString("logo", "");
                    logos.add(logo.isEmpty() ? null : Net.image(logo, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
                }
                runOnUiThread(() -> showNew(items, imgs, logos, sigNow));
            } catch (Exception ignored) { }
        }).start();
    }

    private void showNew(org.json.JSONArray items, List<Bitmap> imgs, List<Bitmap> logos, String sig) {
        newSig = sig;
        newItems.clear();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) newItems.add(it);
        }
        boolean hadFocus = newTile != null && newTile.isFocused();
        if (newTile != null) {
            handler.removeCallbacks(newTick);
            keepRow.removeView(newTile);
            keepCards.remove(newTile);
        }
        newTile = buildNewTile(imgs, logos);
        keepRow.addView(newTile, 0, llp(dp(NEW_W_DP), -2, 0, 0, dp(10), 0));
        keepCards.add(0, newTile);
        // The two feeds race at startup: if the resume cards got there first they were laid out without the
        // banner and would now run under the weather panel. One resume card is all that fits beside it.
        while (keepCards.size() > 2) {
            View extra = keepCards.remove(keepCards.size() - 1);
            keepRow.removeView(extra);
        }
        if (keepRow.getVisibility() != View.VISIBLE) {
            keepRow.setVisibility(View.VISIBLE);
            keepRow.setAlpha(0f);
            keepRow.setTranslationY(dp(10));
            keepRow.animate().alpha(1f).translationY(0)
                    .setInterpolator(new DecelerateInterpolator(2f)).setDuration(260).start();
        }
        rewireKeep();
        startNewRotation();
        if (hadFocus) newTile.requestFocus();
    }

    /** Feed emptied (nothing new left, or Plex removed the file): take the tile away, keep the row. */
    private void hideNewTile() {
        if (newTile == null) return;
        handler.removeCallbacks(newTick);
        keepRow.removeView(newTile);
        keepCards.remove(newTile);
        newTile = null;
        newItems.clear();
        newLayers.clear();
        newSig = "";
        if (keepRow.getChildCount() == 0) keepRow.setVisibility(View.GONE);
        rewireKeep();
    }

    private View buildNewTile(List<Bitmap> imgs, List<Bitmap> logos) {
        final int accent = 0xFFE5A00D;   // Plex orange
        newLayers.clear();
        newCur = 0;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setId(View.generateViewId());
        box.setFocusable(true);
        box.setClickable(true);

        FrameLayout card = new AspectFrame(this);
        card.setFocusable(false);
        card.setClickable(false);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16)); }
        });
        card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{darken(accent, 0.55f), 0xFF14161C}));

        FrameLayout stack = new FrameLayout(this);
        for (int i = 0; i < newItems.size(); i++) {
            View layer = newLayer(newItems.get(i), imgs.get(i), logos.get(i));
            layer.setAlpha(i == 0 ? 1f : 0f);   // only the first frame paints; the rest wait their turn
            newLayers.add(layer);
            stack.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        }
        card.addView(stack, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        newCaption = text(10.5f, "sans-serif", 0xD9FFFFFF);
        newCaption.setSingleLine(true);
        newCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        newCaption.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        box.addView(newCaption, llp(-1, -2, 0, dp(6), 0, 0));
        updateNewCaption();

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(16));
        // A hairline even when idle: the banner reads as a frame, not a fourth resume card.
        ring.setStroke(dp(1), 0x33FFFFFF);
        card.setForeground(ring);
        card.setElevation(dp(6));
        box.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(dp(2), has ? 0xFFFFFFFF : 0x33FFFFFF);
            card.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                    .translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.5f) : new DecelerateInterpolator())
                    .setDuration(has ? 240 : 180).start();
            // Hold the frame while focused: OK must open exactly what is on screen.
            handler.removeCallbacks(newTick);
            if (!has) handler.postDelayed(newTick, 1500);
        });
        box.setOnClickListener(v -> {
            if (newCur >= newItems.size()) return;
            JSONObject it = newItems.get(newCur);
            pressDip(card, 1.05f);
            String uri = it.optString("uri", ""), pkg = it.optString("pkg", "");
            if (!uri.isEmpty()) {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
                if (!pkg.isEmpty()) i.setPackage(pkg);
                if (launch(i)) return;
            }
            if (!pkg.isEmpty() && openPackage(pkg)) return;
            pressUndo(card, 1.05f);
        });
        return box;
    }

    /** One title: still, shade, its logo (or the name) and the chip that says where it came from. */
    private View newLayer(JSONObject it, Bitmap bm, Bitmap logo) {
        FrameLayout layer = new FrameLayout(this);
        boolean live = "live".equals(it.optString("kind"));
        // The chip belongs to the frame, not the tile: the banner rotates between live and new-on-Plex.
        if (live) layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{darken(0xFFD93A32, 0.55f), 0xFF14161C}));
        if (bm != null) {
            ImageView iv = new ImageView(this);
            // A programme still fills the card. A channel logo (the guide had no still) is contained instead,
            // so a 3:1 or square mark is not cropped into a random slice of itself.
            boolean contain = it.optInt("artfit", 0) == 1;
            iv.setScaleType(contain ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            if (contain) {
                iv.setPadding(dp(26), dp(20), dp(26), dp(20));
                iv.setAlpha(0.94f);
            }
            iv.setImageBitmap(bm);
            layer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xC9000000, 0x66000000, 0x00000000}));
        layer.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        capLp.setMargins(dp(10), 0, dp(10), dp(11));
        if (logo != null) {
            ImageView lv = new ImageView(this);
            lv.setImageBitmap(logo);
            lv.setAdjustViewBounds(true);
            lv.setScaleType(ImageView.ScaleType.FIT_START);
            lv.setMaxHeight(dp(30));
            lv.setMaxWidth(dp(200));
            lv.setContentDescription(it.optString("title", ""));
            layer.addView(lv, capLp);
        } else {
            TextView cap = text(11.5f, "sans-serif-medium", Color.WHITE);
            cap.setSingleLine(true);
            cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cap.setText(it.optString("title", ""));
            layer.addView(cap, capLp);
        }
        TextView chip = text(9.5f, "sans-serif-medium", live ? 0xFFF7F7F7 : 0xFFF2C14E);
        SpannableStringBuilder label = new SpannableStringBuilder();
        if (live) {
            label.append("\u25CF ");
            label.setSpan(new ForegroundColorSpan(0xFFFF453A), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        label.append(live ? "LIVE ON TIVIMATE" : "NEW ON PLEX");   // both name the app they open
        chip.setText(label);
        chip.setLetterSpacing(0.18f);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(5));
        pill.setColor(0xCC000000);
        chip.setBackground(pill);
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        layer.addView(chip, lp(-2, -2, Gravity.TOP | Gravity.START, dp(10), dp(10), 0, 0));
        return layer;
    }

    private void updateNewCaption() {
        if (newCaption == null || newCur >= newItems.size()) return;
        JSONObject it = newItems.get(newCur);
        // Title only: at this width the season line pushed it into an ellipsis, and the chip already says
        // it is new. The artwork carries the rest. A live card swaps the title for its channel and end time.
        SpannableStringBuilder meta = new SpannableStringBuilder();
        String caption = it.optString("caption", "");
        coloured(meta, caption.isEmpty() ? it.optString("title", "") : caption, 0xF2FFFFFF);
        setSpan(newCaption, meta);
        newCaption.animate().cancel();
        newCaption.setAlpha(0f);
        newCaption.animate().alpha(1f).setDuration(240).start();
    }

    private void advanceNew() {
        handler.removeCallbacks(newTick);
        // Focus is the pause: a flag could survive a row rebuild and stall the tile for good.
        if (newLayers.size() < 2 || (newTile != null && newTile.isFocused())) return;
        int next = nextFrame();
        if (next < 0) return;
        View in = newLayers.get(next), out = newLayers.get(newCur);
        in.animate().cancel();
        out.animate().cancel();
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(420).start();
        out.animate().alpha(0f).setDuration(420).start();
        newCur = next;
        updateNewCaption();
        handler.postDelayed(newTick, NEW_EVERY_MS);
    }

    /** The next frame that has not finished; -1 when every live programme in the list has ended. */
    private int nextFrame() {
        int n = newLayers.size();
        for (int i = 1; i < n; i++) {   // never returns newCur: re-animating the same frame flashes it
            int cand = (newCur + i) % n;
            if (!expired(newItems.get(cand))) return cand;
        }
        return -1;
    }

    private boolean expired(JSONObject it) {
        long until = it.optLong("until", 0);
        return until > 0 && until <= System.currentTimeMillis() / 1000L;
    }

    private void startNewRotation() {
        handler.removeCallbacks(newTick);
        if (newLayers.size() > 1 && (newTile == null || !newTile.isFocused()))
            handler.postDelayed(newTick, NEW_EVERY_MS);
    }

    // ---------------------------------------------------------------- full-screen settings

    private View settingsTile(String emoji, String label, Intent intent) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x2EFFFFFF);
        bg.setStroke(dp(1), 0x26FFFFFF);
        tile.setBackground(bg);
        TextView e = text(26, "sans-serif", Color.WHITE);
        e.setText(emoji);
        e.setGravity(Gravity.CENTER);
        TextView l = text(13, "sans-serif-medium", 0xF2FFFFFF);
        l.setText(label);
        l.setGravity(Gravity.CENTER);
        tile.addView(e);
        tile.addView(l, llp(-2, -2, 0, dp(8), 0, 0));
        tile.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x2EFFFFFF);
            l.setTextColor(has ? 0xFF111111 : 0xF2FFFFFF);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(180).start();
        });
        tile.setOnClickListener(v -> {
            if (!launch(intent)) launch(new Intent(Settings.ACTION_SETTINGS));
        });
        return tile;
    }

    private Intent amazonSettings(String cls) {
        return new Intent().setComponent(new ComponentName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2." + cls));
    }

    private FrameLayout buildSettingsPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xFA05070D);
        page.setVisibility(View.GONE);
        page.setClickable(true);
        TextView t = text(34, "sans-serif-light", Color.WHITE);
        t.setText("Settings");
        page.addView(t, lp(-2, -2, Gravity.TOP | Gravity.START, dp(80), dp(56), 0, 0));
        TextView sub = text(10, "sans-serif-medium", 0x99FFFFFF);
        sub.setText("PRESS BACK TO RETURN HOME");
        sub.setLetterSpacing(0.22f);
        page.addView(sub, lp(-2, -2, Gravity.TOP | Gravity.START, dp(82), dp(108), 0, 0));

        Object[][] items = {
                {"\uD83D\uDCF6", "Network", amazonSettings("tv.network.NetworkActivity")},
                {"\uD83D\uDD0A", "Display & sounds", amazonSettings("tv.display_sounds.DisplayAndSoundsActivity")},
                {"\uD83E\uDDE9", "Applications", amazonSettings("tv.applications.ApplicationsActivity")},
                {"\uD83D\uDCFA", "My Fire TV", amazonSettings("tv.device.DeviceActivity")},
                {"\u2699\uFE0F", "Preferences", amazonSettings("tv.preferences.PreferencesActivity")},
                {"\u267F", "Accessibility", amazonSettings("tv.accessibility.AccessibilityActivity")},
                {"\uD83D\uDEE0\uFE0F", "Developer options", new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")},
                {"\uD83C\uDFAE", "Controllers & Bluetooth", new Intent(Settings.ACTION_SETTINGS)},
                {"\u2630", "All Amazon settings", new Intent(Settings.ACTION_SETTINGS)}};
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                Object[] it = items[r * 3 + c];
                row.addView(settingsTile((String) it[0], (String) it[1], (Intent) it[2]), llp(dp(210), dp(92), dp(8), dp(8), dp(8), dp(8)));
            }
            rows.addView(row);
        }
        page.addView(rows, lp(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(150), 0, 0));
        return page;
    }

    private void showSettings() {
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        settingsPage.setVisibility(View.VISIBLE);
        settingsPage.setAlpha(0f);
        settingsPage.animate().alpha(1f).setDuration(180).start();
        LinearLayout rows = (LinearLayout) settingsPage.getChildAt(2);
        View first = ((LinearLayout) rows.getChildAt(0)).getChildAt(0);
        first.requestFocus();
    }

    private void hideSettings() {
        settingsPage.setVisibility(View.GONE);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (settingsPill != null) settingsPill.requestFocus();
    }

    // ---------------------------------------------------------------- Home Assistant panel

    private View buildHaPanel() {
        haPanel = new LinearLayout(this);
        haPanel.setOrientation(LinearLayout.VERTICAL);
        haPanel.setPadding(dp(22), dp(16), dp(22), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0x73000000);
        bg.setStroke(dp(1), 0x26FFFFFF);
        haPanel.setBackground(bg);
        haPanel.setElevation(dp(10));
        haPanel.setVisibility(View.GONE);
        haBig = text(40, "sans-serif-thin", Color.WHITE);
        haSub = text(10, "sans-serif-medium", 0xCCFFFFFF);
        haSub.setLetterSpacing(0.18f);
        haL1 = text(12, "sans-serif", 0xF2FFFFFF);
        haL2 = text(12, "sans-serif", 0xE6FFFFFF);
        haL3 = text(12, "sans-serif", 0xE6FFFFFF);
        haL4 = text(12, "sans-serif", 0xE6FFFFFF);
        haL5 = text(12, "sans-serif", 0xE6FFFFFF);
        haPanel.addView(haBig);
        haPanel.addView(haSub, llp(-2, -2, 0, 0, 0, dp(10)));
        for (TextView t : new TextView[]{haL1, haL2, haL3, haL4, haL5}) haPanel.addView(t, llp(-2, -2, 0, dp(3), 0, 0));
        return haPanel;
    }

    private void fetchHa() {
        final String url = getString(R.string.ha_json_url);
        if (url.isEmpty()) return;
        new Thread(() -> {
            final String body = Net.text(url, 4000, 4000, Net.MAX_JSON_BYTES);
            if (body == null) return;
            try {
                final JSONObject j = new JSONObject(body);
                getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("ha_json", body).apply();   // for the Home-key cover
                runOnUiThread(() -> showHa(j));
            } catch (Exception ignored) { }
        }).start();
    }

    private void setLine(TextView t, String s) {
        t.setText(s);
        t.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static String weatherEmoji(String c) {
        c = c.toLowerCase(Locale.US);
        if (c.contains("thunder") || c.contains("lightning")) return "\u26C8\uFE0F";
        if (c.contains("pour") || c.contains("rain")) return "\uD83C\uDF27\uFE0F";
        if (c.contains("snow") || c.contains("hail")) return "\u2744\uFE0F";
        if (c.contains("fog")) return "\uD83C\uDF2B\uFE0F";
        if (c.contains("wind")) return "\uD83D\uDCA8";
        if (c.contains("partly")) return "\u26C5";
        if (c.contains("cloud")) return "\u2601\uFE0F";
        if (c.contains("night")) return "\uD83C\uDF19";
        if (c.contains("sunny") || c.contains("clear")) return "\u2600\uFE0F";
        return "\uD83C\uDF21\uFE0F";
    }

    private static SpannableStringBuilder coloured(SpannableStringBuilder sb, String t, int color) {
        int st = sb.length();
        sb.append(t);
        sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void setSpan(TextView t, SpannableStringBuilder sb) {
        t.setText(sb);
        t.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void showHa(JSONObject j) {
        String cond = j.optString("condition", "");
        haBig.setText(weatherEmoji(cond) + "  " + j.optString("temp", "--") + "\u00B0");
        String feels = j.optString("feels", "");
        haSub.setText((cond.toUpperCase(Locale.getDefault()) + (feels.isEmpty() ? "" : "  \u00B7  FEELS " + feels + "\u00B0")).trim());

        String rain = j.optString("rain", "");
        boolean wet = rain.startsWith("Raining") || rain.contains("on the way");
        SpannableStringBuilder r = new SpannableStringBuilder();
        if (!rain.isEmpty()) coloured(r, (wet ? "\u2614  " : "\uD83C\uDF24\uFE0F  ") + rain, wet ? 0xFF7CC4FF : 0xFF8FE3C8);
        setSpan(haL1, r);

        SpannableStringBuilder p = new SpannableStringBuilder();
        String solar = j.optString("solar", "");
        if (!solar.isEmpty()) {
            int soc = j.optInt("battery", 0);
            coloured(p, "\u2600\uFE0F " + solar + " kW", 0xFFFFC857);
            coloured(p, "     \uD83D\uDD0B " + soc + "%", soc < 20 ? 0xFFFF6B6B : soc < 50 ? 0xFFFFC857 : 0xFF5BE3A1);
            coloured(p, "     \uD83C\uDFE0 " + j.optString("house", "--") + " kW", 0xFF9AB0FF);
        }
        setSpan(haL2, p);

        String bins = j.optString("bins", "");
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (!bins.isEmpty()) coloured(b, "\uD83D\uDDD1\uFE0F  " + bins, bins.startsWith("General") ? 0xFFFF8A80 : bins.startsWith("Recycl") ? 0xFFFFE066 : 0xFFFFFFFF);
        setSpan(haL3, b);

        String next = j.optString("next", "");
        SpannableStringBuilder n = new SpannableStringBuilder();
        if (!next.isEmpty()) coloured(n, "\uD83D\uDCC5  " + next, 0xFFC9B6FF);
        setSpan(haL4, n);

        String inside = j.optString("inside", "");
        String ac = j.optString("ac", "");
        SpannableStringBuilder in = new SpannableStringBuilder();
        if (!inside.isEmpty()) coloured(in, "\uD83C\uDF21\uFE0F  " + inside + "\u00B0 inside" + (ac.isEmpty() ? "" : "     \u2744\uFE0F AC " + ac), 0xFF9FE8FF);
        setSpan(haL5, in);

        if (haPanel.getVisibility() != View.VISIBLE) {
            haPanel.setVisibility(View.VISIBLE);
            if (!haShown) {   // settle in on the first refresh only; later ones are just new numbers
                haShown = true;
                haPanel.setAlpha(0f);
                haPanel.setTranslationY(dp(8));
                haPanel.animate().alpha(1f).translationY(0)
                        .setInterpolator(new DecelerateInterpolator(2f)).setDuration(240).start();
            }
        }
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        wifiPill = pill("Wi-Fi", "");
        setNet("Wi-Fi", false);
        wifiPill.setOnClickListener(v -> openWifiSettings());
        TextView settings = pill("Settings", "⚙");
        settingsPill = settings;
        settings.setOnClickListener(v -> openFullSettings());
        settings.setOnLongClickListener(v -> {
            getSharedPreferences("launcher", MODE_PRIVATE).edit().remove("hidden").remove("dock_order").apply();
            android.widget.Toast.makeText(this, "Hidden apps and main row reset", android.widget.Toast.LENGTH_SHORT).show();
            buildApps();
            return true;
        });
        List<TextView> bar = new ArrayList<>();
        if (hasPassthroughInputs()) {   // HDMI-in devices only (Fire TV Cube, TVs); sticks have none
            TextView inputs = pill("Inputs", "");
            inputs.setText("Inputs");
            android.graphics.drawable.Drawable ic = getDrawable(R.drawable.ic_input);
            ic.setBounds(0, 0, dp(16), dp(16));
            inputs.setCompoundDrawables(ic, null, null, null);
            inputs.setCompoundDrawablePadding(dp(8));
            inputs.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
            inputs.setOnClickListener(v -> showInputs(v));
            bar.add(inputs);
        }
        bar.add(wifiPill);
        bar.add(settings);
        for (TextView p : bar) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            ((TextView) v).setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(has ? 0xFF111111 : 0xE6FFFFFF));
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); glideTo(0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || userHidden().contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockOrder()) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        final android.content.SharedPreferences lp = getSharedPreferences("launches", MODE_PRIVATE);
        Collections.sort(rest, (x, y) -> {   // most recently opened first, then A-Z
            int c = Long.compare(lp.getLong(y.pkg, 0), lp.getLong(x.pkg, 0));
            return c != 0 ? c : x.label.compareToIgnoreCase(y.label);
        });

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        focusNow(last != null ? last : (dockCards.isEmpty() ? null : dockCards.get(0)));
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        if (app.isBanner) bg.setColor(0xFF1C1C1E);
        else {   // no banner art (sideloaded apps such as TiviMate): app-coloured gradient with a soft highlight
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            bg.setColors(new int[]{darken(app.color, 0.85f), darken(app.color, 0.4f), darken(app.color, 0.22f)});
        }
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(30), dp(10), dp(30), dp(26));
            View glowDisc = new View(this);   // light pooling behind the icon
            GradientDrawable gd = new GradientDrawable();
            gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            gd.setGradientRadius(dp(90));
            gd.setGradientCenter(0.5f, 0.42f);
            gd.setColors(new int[]{(0x55 << 24) | (0x00FFFFFF & app.color), 0x00000000});
            glowDisc.setBackground(gd);
            card.addView(glowDisc, 0, new FrameLayout.LayoutParams(-1, -1));
            TextView nm = text(10, "sans-serif-medium", 0xCCFFFFFF);
            nm.setText(app.label);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(dp(8), 0, dp(8), dp(6));
            card.addView(nm, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        boolean pale = averageLuma(app.art) > 0.6f;   // light tile: a white ring would vanish, so use a dark one
        ring.setStroke(dp(pale ? 3 : 2), pale ? 0xF2101218 : 0xE6FFFFFF);
        ring.setCornerRadius(dp(pale ? 8.5f : 9f));   // stroke is drawn half a width inside the bounds; match the tile's 10dp outline
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setLongClickable(true);
        card.setOnLongClickListener(v -> { showAppMenu(v, app); return true; });
        card.setOnClickListener(v -> {
            pressDip(v, inDock ? 1.12f : 1.1f);
            getSharedPreferences("launches", MODE_PRIVATE).edit().putLong(app.pkg, System.currentTimeMillis()).apply();
            boolean opened = launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (!opened) pressUndo(v, inDock ? 1.12f : 1.1f);
        });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                // Moving along the row swaps the name instantly; a short fade over the new one softens the jump
                if (!app.label.contentEquals(title.getText())) {
                    title.setText(app.label);
                    title.setAlpha(0.35f);
                }
                title.animate().alpha(1f).setDuration(220).start();
                animateGlow(app.color);
                fadeGlow(1f);
                glideTo(0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    private void rewireKeep() {
        int d = dockCards.size(), k = keepCards.size();
        if (d == 0) return;
        int topId = pills.isEmpty() ? View.NO_ID : pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            dockCards.get(i).setNextFocusUpId(k == 0 ? topId : keepCards.get(Math.min(k - 1, i * k / d)).getId());
        }
        for (int i = 0; i < k; i++) {
            View c = keepCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusDownId(dockCards.get(Math.min(d - 1, i * d / Math.max(1, k))).getId());
            c.setNextFocusLeftId(i == 0 ? c.getId() : keepCards.get(i - 1).getId());
            c.setNextFocusRightId(i == k - 1 ? c.getId() : keepCards.get(i + 1).getId());
        }
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        rewireKeep();
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void focusNow(View v) {
        if (v != null) v.requestFocus();
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        glideTo(target);
    }

    /** ScrollView.smoothScrollTo runs a fixed ~250 ms whatever the distance, so a five-row jump reads as a
     *  teleport. This scales the duration with the distance travelled (220 ms for one row, capped at 430)
     *  and eases out; a second call mid-glide continues from where the scroll actually is. */
    private void glideTo(int y) {
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        int target = Math.max(0, Math.min(y, max));
        int from = scroll.getScrollY();
        if (target == from) return;
        if (scrollGlide != null) scrollGlide.cancel();
        View firstRow = grid.getChildCount() > 0 ? grid.getChildAt(0) : null;
        int rowPx = firstRow != null && firstRow.getHeight() > 0 ? firstRow.getHeight() : dp(150);
        int rows = Math.max(1, Math.round(Math.abs(target - from) / (float) rowPx));
        ValueAnimator a = ValueAnimator.ofInt(from, target);
        a.setDuration(Math.min(430L, 220L + 55L * (rows - 1)));
        a.setInterpolator(new DecelerateInterpolator(1.8f));
        a.addUpdateListener(anim -> scroll.scrollTo(0, (int) anim.getAnimatedValue()));
        scrollGlide = a;
        a.start();
    }

    /** Half a press before the launch. The gap between OK and the app's first frame is a few hundred ms,
     *  and without this the remote gets no acknowledgement at all in that time. */
    private void pressDip(View v, float base) {
        v.animate().scaleX(base * 0.94f).scaleY(base * 0.94f).setDuration(90)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Only if nothing opened: the tile would otherwise stay slightly small until focus moves. */
    private void pressUndo(View v, float base) {
        v.animate().scaleX(base).scaleY(base).setDuration(160)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    // ---------------------------------------------------------------- inputs

    private String pendingFocusPkg;

    private List<String> dockOrder() {
        String saved = getSharedPreferences("launcher", MODE_PRIVATE).getString("dock_order", null);
        return saved != null ? new ArrayList<>(Arrays.asList(saved.split(","))) : new ArrayList<>(Arrays.asList(dockApps));
    }

    private void saveDock(List<String> order, String focusPkg) {
        StringBuilder sb = new StringBuilder();
        for (String o : order) sb.append(sb.length() == 0 ? "" : ",").append(o);
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("dock_order", sb.toString()).apply();
        pendingFocusPkg = focusPkg;
        buildApps();
    }

    private Set<String> userHidden() {
        return new HashSet<>(getSharedPreferences("launcher", MODE_PRIVATE).getStringSet("hidden", new HashSet<>()));
    }

    /** Long-press (hold OK) on an app: hide it, open its info page, or uninstall it. */
    private void showAppMenu(View anchor, App app) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        PopupWindow pw = new PopupWindow(list, dp(220), -2, true);
        List<TextView> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (View c : dockCards) cur.add(((App) c.getTag()).pkg);
        int at = cur.indexOf(app.pkg);
        if (at >= 0) {
            if (at > 0) rows.add(inputRow("Move left", () -> { Collections.swap(cur, at, at - 1); saveDock(cur, app.pkg); }));
            if (at < cur.size() - 1) rows.add(inputRow("Move right", () -> { Collections.swap(cur, at, at + 1); saveDock(cur, app.pkg); }));
            rows.add(inputRow("Move to apps list", () -> { cur.remove(app.pkg); saveDock(cur, app.pkg); }));
        } else {
            rows.add(inputRow("Add to main row", () -> {
                if (cur.size() >= 8) { android.widget.Toast.makeText(this, "Main row is full - move one out first", android.widget.Toast.LENGTH_LONG).show(); return; }
                cur.add(app.pkg);
                saveDock(cur, app.pkg);
            }));
        }
        rows.add(inputRow("Hide from Home", () -> {
            Set<String> h = userHidden();
            h.add(app.pkg);
            getSharedPreferences("launcher", MODE_PRIVATE).edit().putStringSet("hidden", h).apply();
            buildApps();
        }));
        rows.add(inputRow("App info", () -> openAppPage(app.pkg)));
        try {
            if ((getPackageManager().getApplicationInfo(app.pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0)
                rows.add(inputRow("Uninstall", () -> uninstall(app.pkg)));
        } catch (PackageManager.NameNotFoundException ignored) { }
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(220), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int y = loc[1] + anchor.getHeight() + dp(6);
        if (y + list.getMeasuredHeight() > screenH - dp(16)) y = Math.max(dp(8), loc[1] - list.getMeasuredHeight() - dp(6));
        pw.showAtLocation(anchor, Gravity.TOP | Gravity.START, Math.max(dp(8), loc[0]), y);
        rows.get(0).requestFocus();
    }

    /** Amazon's app page carries the only Uninstall that works: Fire OS accepts ACTION_DELETE from a
     *  third-party caller and then drops it, so the installer never appears. On other devices the
     *  normal uninstall dialog is used, with the app page as the fallback. */
    private void uninstall(String pkg) {
        if (!"Amazon".equalsIgnoreCase(Build.MANUFACTURER)
                && launch(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)))) return;
        android.widget.Toast.makeText(this, "Press Uninstall on this screen", android.widget.Toast.LENGTH_LONG).show();
        openAppPage(pkg);
    }

    private void openAppPage(String pkg) {
        if (!launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))))
            openFullSettings();
    }

    private boolean hasPassthroughInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return false;
        for (TvInputInfo t : tim.getTvInputList()) if (t.isPassthroughInput() && !t.isHidden(this) && !t.getId().startsWith("com.amazon.") && !t.getId().toLowerCase().contains("arcinput")) return true;
        return false;
    }

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this) && !t.getId().startsWith("com.amazon.") && !t.getId().toLowerCase().contains("arcinput")) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText((h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING")
                + (familyName.isEmpty() ? "" : ",  " + familyName.toUpperCase(Locale.getDefault())));
        timeTint.setBackgroundColor(h >= 18 || h < 5 ? 0x26FF7A2E : h < 10 ? 0x1A7FB8FF : 0x00000000);
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault())
                + (deviceName.isEmpty() ? "" : "   \u00B7   " + deviceName.toUpperCase(Locale.getDefault())));
    }

    private void setNet(String label, boolean ethernet) {
        wifiPill.setText(label);
        android.graphics.drawable.Drawable ic = getDrawable(ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi);
        int sz = dp(16);
        ic.setBounds(0, 0, sz, sz);
        wifiPill.setCompoundDrawables(ic, null, null, null);
        wifiPill.setCompoundDrawablePadding(dp(8));
        wifiPill.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(wifiPill.isFocused() ? 0xFF111111 : 0xE6FFFFFF));
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_ETHERNET) {
            setNet("Ethernet", true);
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Offline";
                else if (wm.getConnectionInfo().getLinkSpeed() > 0) name += "  \u00B7  " + wm.getConnectionInfo().getLinkSpeed() + " Mbps";
            }
        } catch (SecurityException ignored) { }
        setNet(name, false);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        glowNow = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        currentWall = bm;
        wallFront.setAlpha(0f);
        wallFront.setScaleX(1f);
        wallFront.setScaleY(1f);
        final ImageView kb = wallFront;
        android.animation.ValueAnimator drift = android.animation.ValueAnimator.ofFloat(1f, 1.07f);
        drift.setDuration(WALLPAPER_INTERVAL_MS + 4000);
        drift.setInterpolator(new android.view.animation.LinearInterpolator());
        drift.addUpdateListener(a -> { float f = (float) a.getAnimatedValue(); kb.setScaleX(f); kb.setScaleY(f); });
        drift.start();
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private static float averageLuma(Drawable d) {
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.TRANSPARENT);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float sum = 0, n = 0;
            for (int x = 0; x < 24; x++) for (int y = 0; y < 14; y++) {
                int px = bm.getPixel(x, y);
                float a = Color.alpha(px) / 255f;
                float l = (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f;
                sum += l * a + 0f * (1 - a);   // transparent counts as dark: the tile background is dark there
                n++;
            }
            return sum / n;
        } catch (RuntimeException e) { return 0f; }
    }

    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        return i != null && launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        kickIdle();
        // The dock <-> keep-watching hop is routed here rather than by nextFocusUpId: the framework's
        // focus search kept falling back to the top bar even with the ids set correctly.
        if (e.getAction() == KeyEvent.ACTION_DOWN && !keepCards.isEmpty() && !dockCards.isEmpty()) {
            int i = dockCards.indexOf(getCurrentFocus());
            if (i >= 0 && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                keepCards.get(Math.min(keepCards.size() - 1, i * keepCards.size() / dockCards.size())).requestFocus();
                return true;
            }
            int k = keepCards.indexOf(getCurrentFocus());
            if (k >= 0) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                    dockCards.get(Math.min(dockCards.size() - 1, k * dockCards.size() / keepCards.size())).requestFocus();
                    return true;
                }
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                    int to = Math.max(0, Math.min(keepCards.size() - 1,
                            k + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                    if (to != k) keepCards.get(to).requestFocus();
                    return true;   // the ends of the row hold, they don't fall through to another row
                }
            }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_SETTINGS && e.getAction() == KeyEvent.ACTION_UP) { openFullSettings(); return true; }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
