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
    private TextView greeting, clock, ampm, date, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private ValueAnimator scrollGlide;
    private boolean haShown;
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
    private LinearLayout haPanel;
    private Frost haFrost;
    private TextView haBig, haSub, haFeels, haRain, haNext;
    private View haRainRow, haNextRow;
    // Six tiles in two rows of three: solar, battery, house load / inside, AC, bins.
    private static final String[] HA_KEYS = {"solar", "battery", "house", "inside", "ac", "bins"};
    private static final String[] HA_LABELS = {"SOLAR", "BATTERY", "HOME", "INSIDE", "AC", "BINS"};
    private static final int[] HA_ICONS = {R.drawable.ic_solar, R.drawable.ic_battery, R.drawable.ic_house,
            R.drawable.ic_thermo, R.drawable.ic_ac, R.drawable.ic_bins};
    private final View[] haTileBox = new View[6];
    private final TextView[] haTileValue = new TextView[6];
    private final TextView[] haTileLabel = new TextView[6];
    private final ImageView[] haTileIcon = new ImageView[6];
    /** The one accent colour on the home screen, and it only ever means "look at this". */
    private static final int HA_ALERT = 0xFFFFC857;
    private static final int HA_LABEL = 0x99FFFFFF;
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
        @Override public void run() { fetchHa(); if (pollN++ % 20 == 0) fetchHero(); handler.postDelayed(this, 15000); }
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
        startHeroRotation();
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
        handler.removeCallbacks(heroTick);
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

        // Hero: exactly one screen tall, header on top, banner + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));
        hero.addView(buildHaPanel(), lp(dp(400), -2, Gravity.END | Gravity.TOP, 0, dp(96), dp(48), 0));

        // A soft floor behind the dock: the band and its icons never sit on raw artwork.
        View dockFade = new View(this);
        dockFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x3D000000, 0x8A000000}));
        hero.addView(dockFade, lp(-1, dp(150), Gravity.BOTTOM, 0, 0, 0, 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(24)));
        bottom.addView(buildHero(), 0);

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(24));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        dockRow.setElevation(dp(12));   // lifts the tray off the photo instead of pasting it on
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(Typo.CAPTION, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(Typo.LABEL_TRACKING);
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

    // ---------------------------------------------------------------- hero banner

    // One wide banner replaces the old "new on Plex" tile plus the resume cards: live sport, then
    // what you were part-way through, then what is new. 2.37:1 - wide enough to read as a banner,
    // short enough that the 96sp clock and the dock keep their room.
    private static final long HERO_EVERY_MS = 9000;
    private static final long HERO_FADE_MS = 600;
    private static final int HERO_W_DP = 460;
    private static final int HERO_H_DP = 186;
    // Four live programmes, two resumes and two new titles: sixteen dots would be a wall of
    // gravel and a two-minute cycle before returning to what is live now.
    private static final int HERO_MAX = 8;

    private View heroBox;
    private FrameLayout heroCard, heroStack;
    private LinearLayout heroDots;
    private final List<View> heroLayers = new ArrayList<>();
    private final List<JSONObject> heroItems = new ArrayList<>();
    private final List<View> heroDotsView = new ArrayList<>();
    private int heroCur;
    private boolean heroShown;
    private String heroSig = "";
    private final Runnable heroTick = this::advanceHero;

    private View buildHero() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setVisibility(View.GONE);
        heroBox = box;

        heroCard = new FrameLayout(this);
        heroCard.setId(View.generateViewId());
        heroCard.setFocusable(true);
        heroCard.setClickable(true);
        heroCard.setClipToOutline(true);
        heroCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(24));
            }
        });
        heroCard.setBackgroundColor(0xFF14161C);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(24));
        ring.setStroke(dp(1), 0x33FFFFFF);
        heroCard.setForeground(ring);
        heroCard.setElevation(dp(8));

        heroStack = new FrameLayout(this);
        heroCard.addView(heroStack, new FrameLayout.LayoutParams(-1, -1));
        box.addView(heroCard, new LinearLayout.LayoutParams(dp(HERO_W_DP), dp(HERO_H_DP)));

        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        heroDots.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.setMargins(0, dp(10), 0, 0);
        box.addView(heroDots, dlp);

        heroCard.setOnClickListener(v -> openHero(heroCur));
        heroCard.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(dp(has ? 2 : 1), has ? 0xE6FFFFFF : 0x33FFFFFF);
            v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f)
                    .translationZ(has ? dp(12) : 0).setDuration(has ? 170 : 220).start();
            // Focus is the pause: OK must open exactly what is on screen.
            handler.removeCallbacks(heroTick);
            if (!has) handler.postDelayed(heroTick, 1500);
        });
        return box;
    }

    /**
     * Both feeds, merged: live programmes first, then what you are part-way through, then what is
     * new. The old code kept these as separate rows; one banner can only show one thing at a time,
     * so the order is the priority.
     */
    private void fetchHero() {
        final String newUrl = getString(R.string.new_json_url);
        final String keepUrl = getString(R.string.keep_json_url);
        if (newUrl.isEmpty() && keepUrl.isEmpty()) return;
        new Thread(() -> {
            org.json.JSONArray fresh = readItems(newUrl);
            org.json.JSONArray keep = readItems(keepUrl);
            List<JSONObject> items = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int pass = 0; pass < 3; pass++) {
                org.json.JSONArray src = pass == 1 ? keep : fresh;
                if (src == null) continue;
                for (int i = 0; i < src.length(); i++) {
                    JSONObject it = src.optJSONObject(i);
                    if (it == null) continue;
                    boolean live = "live".equals(it.optString("kind"));
                    if ((pass == 0 && !live) || (pass == 2 && live)) continue;
                    String key = it.optString("title", "").toLowerCase(Locale.US) + "|" + it.optString("sub", "");
                    if (!"live".equals(it.optString("kind")) && !seen.add(key)) continue;
                    items.add(it);
                    if (items.size() >= HERO_MAX) break;
                }
                if (items.size() >= HERO_MAX) break;
            }
            if (items.isEmpty()) {
                runOnUiThread(this::hideHero);
                return;
            }
            StringBuilder sig = new StringBuilder();
            for (JSONObject it : items) {
                sig.append(it.optString("title", "")).append('|')
                        .append(it.optString("image", "")).append('|')
                        .append(it.optLong("until", 0)).append('|')
                        .append(it.optDouble("progress", 0)).append('|');
            }
            final String sigNow = sig.toString();
            // Same list as the one on screen: rebuilding would restart the crossfade and download
            // every still again.
            if (heroCard != null && sigNow.equals(heroSig)) return;
            final List<Bitmap> imgs = new ArrayList<>(), logos = new ArrayList<>();
            for (JSONObject it : items) {
                String img = it.optString("image", "");
                imgs.add(img.isEmpty() ? null : Net.image(img, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
                String logo = it.optString("logo", "");
                logos.add(logo.isEmpty() ? null : Net.image(logo, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
            }
            runOnUiThread(() -> showHero(items, imgs, logos, sigNow));
        }).start();
    }

    private static org.json.JSONArray readItems(String url) {
        if (url.isEmpty()) return null;
        String body = Net.text(url, 4000, 4000, Net.MAX_JSON_BYTES);
        if (body == null) return null;
        try {
            return new JSONObject(body).optJSONArray("items");
        } catch (Exception e) {
            return null;
        }
    }

    private void showHero(List<JSONObject> items, List<Bitmap> imgs, List<Bitmap> logos, String sig) {
        boolean hadFocus = heroCard != null && heroCard.isFocused();
        heroSig = sig;
        heroItems.clear();
        heroItems.addAll(items);
        heroLayers.clear();
        heroDotsView.clear();
        heroStack.removeAllViews();
        heroDots.removeAllViews();
        heroCur = 0;
        for (int i = 0; i < heroItems.size(); i++) {
            View layer = heroLayer(heroItems.get(i), imgs.get(i), logos.get(i));
            layer.setAlpha(i == 0 ? 1f : 0f);
            heroLayers.add(layer);
            heroStack.addView(layer, new FrameLayout.LayoutParams(-1, -1));
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            dot.setBackground(d);
            heroDots.addView(dot, dotParams(i == 0));
            heroDotsView.add(dot);
        }
        heroDots.setVisibility(heroItems.size() > 1 ? View.VISIBLE : View.GONE);
        heroBox.setVisibility(View.VISIBLE);
        if (!heroShown) {
            heroShown = true;
            heroBox.setAlpha(0f);
            heroBox.setTranslationY(dp(10));
            heroBox.animate().alpha(1f).translationY(0)
                    .setInterpolator(new DecelerateInterpolator(2f)).setDuration(260).start();
        }
        if (hadFocus) heroCard.requestFocus();
        startHeroRotation();
    }

    private LinearLayout.LayoutParams dotParams(boolean active) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(active ? 8 : 6), dp(active ? 8 : 6));
        p.setMargins(0, 0, dp(8), 0);
        return p;
    }

    /** One frame: artwork, the source chip, the title (or its clearlogo) and a single meta line. */
    private View heroLayer(JSONObject it, Bitmap bm, Bitmap logo) {
        FrameLayout layer = new FrameLayout(this);
        boolean live = "live".equals(it.optString("kind"));
        boolean contain = it.optInt("artfit", 0) == 1;
        if (live) layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{darken(0xFFD93A32, 0.55f), 0xFF14161C}));
        if (bm != null) {
            ImageView art = new ImageView(this);
            art.setScaleType(contain ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            if (contain) {   // a channel logo, not a still: show the whole mark instead of a slice
                art.setPadding(dp(96), dp(24), dp(96), dp(24));
                art.setAlpha(0.94f);
            }
            art.setImageBitmap(bm);
            layer.addView(art, new FrameLayout.LayoutParams(-1, -1));
        }
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x8C000000, 0x00000000}));
        layer.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        String kind = it.optString("kind");
        String chipText = live ? "LIVE ON TIVIMATE" : "new".equals(kind) ? "NEW ON PLEX" : "CONTINUE WATCHING";
        TextView chip = text(Typo.CAPTION, "sans-serif-medium", live ? 0xFFF7F7F7 : 0xFFF2C14E);
        SpannableStringBuilder chipLabel = new SpannableStringBuilder();
        if (live) {
            chipLabel.append("\u25CF ");
            chipLabel.setSpan(new ForegroundColorSpan(0xFFFF453A), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        chipLabel.append(chipText);
        chip.setText(chipLabel);
        chip.setLetterSpacing(0.06f);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(8));
        pill.setColor(0xCC000000);
        chip.setBackground(pill);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        layer.addView(chip, lp(-2, -2, Gravity.TOP | Gravity.START, dp(16), dp(16), 0, 0));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        if (logo != null) {
            ImageView lv = new ImageView(this);
            lv.setImageBitmap(logo);
            lv.setAdjustViewBounds(true);
            lv.setScaleType(ImageView.ScaleType.FIT_START);
            lv.setMaxHeight(dp(40));
            lv.setMaxWidth(dp(260));
            lv.setContentDescription(it.optString("title", ""));
            info.addView(lv);
        } else {
            TextView title = text(Typo.HEADING, "sans-serif-semibold", Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setText(it.optString("title", ""));
            title.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
            info.addView(title);
        }
        TextView meta = text(Typo.CAPTION, "sans-serif", 0xD9FFFFFF);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setText(heroMeta(it));
        info.addView(meta, llp(-1, -2, 0, dp(2), 0, 0));
        layer.addView(info, lp(-1, -2, Gravity.BOTTOM, dp(20), 0, dp(20), dp(18)));

        double prog = it.optDouble("progress", 0);
        if (prog > 0) {
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
            fb.setColor(0xFFFFFFFF);
            bar.setBackground(fb);
            track.addView(bar, new FrameLayout.LayoutParams(
                    Math.max(dp(6), (int) (dp(HERO_W_DP - 40) * Math.min(1.0, prog))), dp(3)));
            layer.addView(track, lp(-1, dp(3), Gravity.BOTTOM, dp(20), 0, dp(20), dp(10)));
        }
        return layer;
    }

    /** The one line under the title: where it comes from, and either when it ends or how long is left. */
    private String heroMeta(JSONObject it) {
        boolean live = "live".equals(it.optString("kind"));
        if (live) {
            String channel = it.optString("channel", "");
            long until = it.optLong("until", 0);
            StringBuilder sb = new StringBuilder("LIVE");
            if (!channel.isEmpty()) sb.append("  \u00B7  ").append(channel);
            if (until > 0) sb.append("  \u00B7  Ends ").append(
                    Times.clockTime(until, android.text.format.DateFormat.is24HourFormat(this), java.time.ZoneId.systemDefault()));
            return sb.toString();
        }
        String app = it.optString("app", "Plex").toUpperCase(Locale.getDefault());
        String sub = it.optString("sub", "");
        return sub.isEmpty() ? app : app + "  \u00B7  " + sub;
    }

    private void advanceHero() {
        handler.removeCallbacks(heroTick);
        if (heroLayers.size() < 2 || heroCard == null || heroCard.isFocused()) return;
        int next = nextHeroFrame();
        if (next < 0) return;
        showHeroFrame(next);
        handler.postDelayed(heroTick, HERO_EVERY_MS);
    }

    /** Manual step from the D-pad; restarts the dwell so a stepped-to frame gets its full time. */
    private void stepHero(int dir) {
        if (heroLayers.size() < 2) return;
        int next = Math.floorMod(heroCur + dir, heroLayers.size());
        if (next == heroCur) return;
        showHeroFrame(next);
        handler.removeCallbacks(heroTick);
        if (heroCard != null && !heroCard.isFocused()) handler.postDelayed(heroTick, HERO_EVERY_MS);
    }

    private void showHeroFrame(int next) {
        View in = heroLayers.get(next), out = heroLayers.get(heroCur);
        in.animate().cancel();
        out.animate().cancel();
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(HERO_FADE_MS).start();
        out.animate().alpha(0f).setDuration(HERO_FADE_MS).start();
        heroCur = next;
        updateHeroDots();
    }

    private void updateHeroDots() {
        for (int i = 0; i < heroDotsView.size(); i++) {
            boolean active = i == heroCur;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(active ? 0xFFFFFFFF : 0x59FFFFFF);
            heroDotsView.get(i).setBackground(d);
            heroDotsView.get(i).setLayoutParams(dotParams(active));
        }
    }

    /** The next frame that has not finished; -1 when every live programme in the list has ended. */
    private int nextHeroFrame() {
        int n = heroLayers.size();
        for (int i = 1; i < n; i++) {   // never returns heroCur: re-animating the same frame flashes it
            int cand = (heroCur + i) % n;
            if (!expired(heroItems.get(cand))) return cand;
        }
        return -1;
    }

    private boolean expired(JSONObject it) {
        long until = it.optLong("until", 0);
        return until > 0 && until <= System.currentTimeMillis() / 1000L;
    }

    private void startHeroRotation() {
        handler.removeCallbacks(heroTick);
        if (heroLayers.size() > 1 && (heroCard == null || !heroCard.isFocused()))
            handler.postDelayed(heroTick, HERO_EVERY_MS);
    }

    private void hideHero() {
        heroSig = "";
        heroItems.clear();
        heroLayers.clear();
        heroDotsView.clear();
        if (heroBox != null) heroBox.setVisibility(View.GONE);
        handler.removeCallbacks(heroTick);
    }

    private void openHero(int i) {
        if (i < 0 || i >= heroItems.size()) return;
        JSONObject it = heroItems.get(i);
        if (expired(it)) return;
        String uri = it.optString("uri", ""), pkg = it.optString("pkg", "");
        if (!uri.isEmpty()) {
            Intent in = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
            if (!pkg.isEmpty()) in.setPackage(pkg);
            if (launch(in)) return;
        }
        if (!pkg.isEmpty() && openPackage(pkg)) return;
    }

    /** The banner sits above the dock: Up from the dock lands on it, Down from it returns. */
    private void rewireHero() {
        if (heroCard == null) return;
        int topId = pills.isEmpty() ? View.NO_ID : pills.get(0).getId();
        heroCard.setNextFocusUpId(topId);
        heroCard.setNextFocusDownId(dockCards.isEmpty() ? heroCard.getId() : dockCards.get(0).getId());
        if (!dockCards.isEmpty()) dockCards.get(0).setNextFocusUpId(heroCard.getId());
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
        haPanel.setPadding(dp(24), dp(16), dp(24), dp(16));
        haFrost = new Frost(haPanel, dp(24), Math.max(1, dp(1)));
        haPanel.setBackground(haFrost);
        haPanel.setElevation(dp(10));
        haPanel.setVisibility(View.GONE);

        // Temperature and condition share a row: stacked, the panel grew a line taller than the
        // six tiles under it and ran into the dock.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        haBig = text(Typo.TEMPERATURE, "sans-serif-light", Color.WHITE);
        Typo.numbers(haBig);
        top.addView(haBig, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout cond = new LinearLayout(this);
        cond.setOrientation(LinearLayout.VERTICAL);
        haSub = text(Typo.CAPTION, "sans-serif-medium", 0xE6FFFFFF);
        haSub.setLetterSpacing(Typo.LABEL_TRACKING);
        haSub.setSingleLine(true);
        haFeels = text(Typo.CAPTION, "sans-serif", HA_LABEL);
        haFeels.setSingleLine(true);
        cond.addView(haSub);
        cond.addView(haFeels, llp(-2, -2, 0, dp(2), 0, 0));
        top.addView(cond, llp(-2, -2, dp(16), 0, 0, dp(10)));
        haPanel.addView(top, llp(-1, -2, 0, 0, 0, dp(10)));

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // Bins needs the widest cell ("General" + "TOMORROW"), AC the narrowest ("Off").
            // Solar carries "10.0 kW" and bins "TOMORROW"; AC only ever says "Off".
            float[] weights = r == 0 ? new float[]{1.08f, 0.96f, 0.96f} : new float[]{1f, 0.72f, 1.28f};
            for (int c = 0; c < 3; c++) {
                View tile = haTile(r * 3 + c);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, weights[c]);
                p.setMargins(c == 0 ? 0 : dp(10), 0, 0, 0);
                row.addView(tile, p);
            }
            haPanel.addView(row, llp(-1, -2, 0, 0, 0, r == 0 ? dp(10) : 0));
        }

        haRain = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haRainRow = haLine(R.drawable.ic_rain, haRain);
        haPanel.addView(haRainRow, llp(-1, -2, 0, dp(10), 0, 0));
        haNext = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haNextRow = haLine(R.drawable.ic_calendar, haNext);
        haPanel.addView(haNextRow, llp(-1, -2, 0, dp(4), 0, 0));
        return haPanel;
    }

    /** One cell: line icon, the value, and the label that says what the value is. */
    private View haTile(int i) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(HA_ICONS[i]);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(HA_LABEL));
        box.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView value = text(Typo.BODY, "sans-serif-semibold", Color.WHITE);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Typo.numbers(value);
        TextView label = text(Typo.CAPTION, "sans-serif", HA_LABEL);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setText(HA_LABELS[i]);
        col.addView(value);
        col.addView(label);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.setMargins(dp(6), 0, 0, 0);
        box.addView(col, cp);
        haTileBox[i] = box;
        haTileValue[i] = value;
        haTileLabel[i] = label;
        haTileIcon[i] = icon;
        return box;
    }

    private View haLine(int iconRes, TextView t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(HA_LABEL));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(8), 0, 0, 0);
        row.addView(t, p);
        return row;
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

    /** Still used by the Home-key cover, which keeps its own compact copy of the panel. */
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

    private void setHaTile(int i, String value, String raw, JSONObject ages, String label) {
        boolean has = !raw.isEmpty() && !raw.startsWith("unknown") && !raw.startsWith("unavailable");
        haTileBox[i].setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        if (!has) return;
        haTileValue[i].setText(value);
        long age = ages == null ? 0 : ages.optLong(HA_KEYS[i], 0);
        boolean stale = Times.stale(age);
        haTileBox[i].setAlpha(stale ? 0.4f : 1f);
        haTileLabel[i].setText(stale ? Times.updatedAgo(age) : label);
    }

    /** The one accent, used only where something needs attention. */
    private void alertTile(int i, boolean alert) {
        haTileValue[i].setTextColor(alert ? HA_ALERT : Color.WHITE);
        haTileIcon[i].setImageTintList(android.content.res.ColorStateList.valueOf(alert ? HA_ALERT : HA_LABEL));
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.getDefault());
    }

    /** "General bins - tomorrow" -> "General"; the tile has room for a word, not a sentence. */
    private static String binsKind(String s) {
        String head = s.split("[-\u2013]")[0].replaceAll("(?i)\\s*bins?\\s*", " ").trim();
        return head.isEmpty() ? "Bins" : head;
    }

    private static String binsWhen(String s) {
        String l = s.toLowerCase(Locale.US);
        if (l.contains("tonight")) return "TONIGHT";
        if (l.contains("tomorrow")) return "TOMORROW";
        return "BINS";
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
        long now = System.currentTimeMillis() / 1000L;
        JSONObject ages = j.optJSONObject("ages");

        haBig.setText(j.optString("temp", "--") + "\u00B0");
        String cond = j.optString("condition", "");
        haSub.setText(cond.toUpperCase(Locale.getDefault()));
        haSub.setVisibility(cond.isEmpty() ? View.GONE : View.VISIBLE);
        String feels = j.optString("feels", "");
        haFeels.setText(feels.isEmpty() ? "" : "FEELS " + feels + "\u00B0");
        haFeels.setVisibility(feels.isEmpty() ? View.GONE : View.VISIBLE);

        String solar = j.optString("solar", "");
        setHaTile(0, solar.isEmpty() ? "" : Times.kw(solar) + " kW", solar, ages, HA_LABELS[0]);
        String battery = j.optString("battery", "");
        setHaTile(1, battery.isEmpty() ? "" : battery + "%", battery, ages, HA_LABELS[1]);
        String house = j.optString("house", "");
        setHaTile(2, house.isEmpty() ? "" : Times.kw(house) + " kW", house, ages, HA_LABELS[2]);
        String inside = j.optString("inside", "");
        setHaTile(3, inside.isEmpty() ? "" : Times.kw(inside) + "\u00B0", inside, ages, HA_LABELS[3]);
        String ac = j.optString("ac", "");
        setHaTile(4, ac.isEmpty() ? "" : capitalise(ac), ac, ages, HA_LABELS[4]);
        String bins = j.optString("bins", "");
        setHaTile(5, bins.isEmpty() ? "" : binsKind(bins), bins, ages,
                bins.isEmpty() ? HA_LABELS[5] : binsWhen(bins));
        alertTile(5, !bins.isEmpty() && !"BINS".equals(binsWhen(bins)));

        String rain = j.optString("rain", "");
        boolean wet = rain.startsWith("Raining") || rain.toLowerCase(Locale.US).contains("on the way");
        boolean dry = rain.startsWith("0.0 mm") || rain.toLowerCase(Locale.US).contains("dry");
        haRainRow.setVisibility(rain.isEmpty() ? View.GONE : View.VISIBLE);
        haRain.setText(wet || !dry ? rain : "No rain expected");
        haRain.setTextColor(wet ? HA_ALERT : 0xF2FFFFFF);

        String next = Times.cleanCalendar(j.optString("next", ""));
        String rel = Times.relative(j.optLong("next_at", 0), now);
        if (!rel.isEmpty()) next = (next.isEmpty() ? "" : next + "   \u00B7   ") + rel;
        haNextRow.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        haNext.setText(next);

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
        greeting = text(Typo.CAPTION, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(Typo.LABEL_TRACKING);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(Typo.CLOCK, "sans-serif-light", Color.WHITE);
        clock.setIncludeFontPadding(false);
        Typo.numbers(clock);
        clockRow.addView(clock);
        ampm = text(Typo.CAPTION, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(Typo.CAPTION, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setEllipsize(android.text.TextUtils.TruncateAt.END);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(Typo.LABEL_TRACKING);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        // 448dp is everything left of the weather panel: 960 - 48 - 400 - 48 - 16 of breathing room.
        header.addView(left, new FrameLayout.LayoutParams(dp(448), -2, Gravity.START | Gravity.TOP));

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
            right.addView(p, llp(-2, dp(44), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(18), 0, dp(18), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
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

        if (!dock.isEmpty()) setGlow(dock.get(0).color);
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        // A pill is never the right place to land after a rebuild: the first focusable view gains
        // focus during layout, which used to pin the cursor to "Inputs" on every cold start.
        if (last != null && pills.contains(last)) last = null;
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
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
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

        // A hairline rim belongs to the tile itself, focused or not. Focus is scale, shadow and one
        // pass of light - not a heavy border drawn round the artwork.
        GradientDrawable rim = new GradientDrawable();
        rim.setStroke(dp(1), 0x33FFFFFF);
        rim.setCornerRadius(dp(14));
        View edge = new View(this);
        edge.setBackground(rim);
        card.addView(edge, new FrameLayout.LayoutParams(-1, -1));

        View sheen = new View(this);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF}));
        sheen.setAlpha(0f);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

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
            pressDip(v, 1.08f);
            getSharedPreferences("launches", MODE_PRIVATE).edit().putLong(app.pkg, System.currentTimeMillis()).apply();
            boolean opened = launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (!opened) pressUndo(v, 1.08f);
        });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s)
                    .translationZ(has ? dp(10) : 0).translationY(has ? -dp(4) : 0)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(has ? 170 : 200).start();
            // One sheen sweep on focus, not a permanent glow: it reads as light passing over glass.
            if (has) {
                int w = card.getWidth() > 0 ? card.getWidth() : dp(120);
                sheen.setTranslationX(-w);
                sheen.setAlpha(0.9f);
                sheen.animate().translationX(w).alpha(0f)
                        .setInterpolator(new DecelerateInterpolator(1.4f)).setDuration(520).start();
            } else {
                sheen.animate().cancel();
                sheen.setAlpha(0f);
            }
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                animateGlow(app.color);
                fadeGlow(1f);
                glideTo(0);
            } else {
                fadeGlow(0.35f);
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
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
            c.setNextFocusUpId(heroCard != null ? heroCard.getId() : topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        rewireHero();
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
        // One formatter for every time on this screen, in the device's own zone.
        clock.setText(Times.clockDigits(now.getTimeInMillis() / 1000L, is24, java.time.ZoneId.systemDefault()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        String day = new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime())
                .toUpperCase(Locale.getDefault());
        String full = day + (deviceName.isEmpty() ? "" : "   \u00B7   " + deviceName.toUpperCase(Locale.getDefault()));
        // Letter-spacing is not counted in measureText, so leave a margin for it. A long device
        // name drops rather than turning the line into "LIVING R...".
        date.setText(date.getPaint().measureText(full) <= dp(424) * 0.92f ? full : day);
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
                // The link speed is the Wi-Fi association's rate, not the broadband speed, so it
                // says "Link" and is left off entirely when the driver will not report it.
                else if (wm.getConnectionInfo().getLinkSpeed() > 0)
                    name += "  \u00B7  Link " + wm.getConnectionInfo().getLinkSpeed() + " Mbps";
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
        // One small blurred copy per photo, for the frosted panel to sample.
        new Thread(() -> {
            final Bitmap f = Blur.frost(bm, 160, 90);
            runOnUiThread(() -> { if (haFrost != null) haFrost.setBlur(f); });
        }).start();
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.atLeastCaption(sp));
        t.setTypeface(Typo.family(this, family));
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
        // The banner <-> dock hop is routed here rather than by nextFocusUpId: the framework's
        // focus search kept falling back to the top bar even with the ids set correctly.
        if (e.getAction() == KeyEvent.ACTION_DOWN && heroCard != null && heroBox.getVisibility() == View.VISIBLE) {
            View now = getCurrentFocus();
            if (dockCards.contains(now) && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                heroCard.requestFocus();
                return true;
            }
            if (now == heroCard) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
                    return true;
                }
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) { stepHero(1); return true; }
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) { stepHero(-1); return true; }
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
