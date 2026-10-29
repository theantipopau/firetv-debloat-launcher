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
    private TextView greeting, clock, wifiPill, appsLabel;
    private View topBar;
    /** The HA values you can see without opening anything: weather, solar, battery, next alert. */
    private LinearLayout haGlance;
    private final LinearLayout[] glanceBox = new LinearLayout[4];
    private final TextView[] glanceValue = new TextView[4];
    private String netLabel = "Wi-Fi";
    private String cwSig = "";
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
    private TextView settingsPill, inputsChip;
    private View timeTint;
    /** The hero copy lives in the upper 55% of the screen; the dock and the row below own the rest. */
    private static final int HERO_COPY_DP = 290;
    private static final long IDLE_MS = 3 * 60 * 1000;
    private final Runnable idle = () -> {
        if (hasWindowFocus() && inputsPopup == null) startActivity(new Intent(this, ClockSaverActivity.class));
    };
    private void kickIdle() { handler.removeCallbacks(idle); handler.postDelayed(idle, IDLE_MS); }
    private String familyName, deviceName;
    private int pollN;
    private final Runnable haPoll = new Runnable() {
        @Override public void run() {
            fetchHa();
            if (pollN++ % 20 == 0) { fetchHero(); fetchKeep(); }
            handler.postDelayed(this, 15000);
        }
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
        if (haOpen) {   // never come back to a sheet that was already sliding out
            haOpen = false;
            if (haPanel != null) {
                haPanel.animate().cancel();
                haPanel.setTranslationX(0);
                haPanel.setAlpha(1f);
                haPanel.setVisibility(View.GONE);
                haScrim.animate().cancel();
                haScrim.setAlpha(0f);
                haScrim.setVisibility(View.GONE);
            }
        }
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
        // The sheet is the only thing Back can close; otherwise Back returns to the dock.
        if (haOpen) { closeHa(); return; }
        View now = getCurrentFocus();
        boolean away = scroll.getScrollY() > 0 || gridCards.contains(now) || cwCards.contains(now);
        if (away && !dockCards.isEmpty()) {
            dockCards.get(0).requestFocus();
            glideTo(0);
        }
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

        // The home screen: one screen tall. Backdrop behind, top bar over it, dock and the
        // continue-watching row at the bottom; the apps grid begins below the fold.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHero(), new FrameLayout.LayoutParams(-1, -1));

        // A soft floor: the dock and the row under it never sit on raw artwork.
        View dockFade = new View(this);
        dockFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x59000000, 0xA6000000}));
        hero.addView(dockFade, lp(-1, dp(230), Gravity.BOTTOM, 0, 0, 0, 0));

        topBar = buildHeader();
        hero.addView(topBar, lp(-1, dp(64), Gravity.TOP, dp(48), dp(16), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, 0, 0, 0, dp(20)));

        // No tray behind the row: the tiles themselves are the shape.
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(48), dp(10), dp(48), dp(10));
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        bottom.addView(buildContinue(), llp(-1, -2, 0, dp(16), 0, 0));

        appsLabel = text(22, "sans-serif-semibold", 0xE6FFFFFF);
        appsLabel.setText("Apps");
        content.addView(appsLabel, llp(-2, -2, dp(48), dp(24), 0, dp(10)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(48), 0, dp(48), dp(160)));

        // Above the scroll view, so the sheet slides over the dock and the row rather than with them.
        haScrim = new View(this);
        haScrim.setBackgroundColor(0x73000000);
        haScrim.setAlpha(0f);
        haScrim.setVisibility(View.GONE);
        haScrim.setClickable(true);
        haScrim.setOnClickListener(v -> closeHa());
        root.addView(haScrim, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildHaPanel(), lp(dp(HA_PANEL_W_DP), -1, Gravity.END, 0, dp(24), dp(24), dp(24)));
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

    private View heroArt;
    private FrameLayout heroTarget, heroStack;
    private LinearLayout cwBlock, cwRow;
    private android.widget.HorizontalScrollView cwScroll;
    private final List<View> cwCards = new ArrayList<>();
    private LinearLayout heroDots;
    private final List<View> heroLayers = new ArrayList<>();
    private final List<Feed.Item> heroItems = new ArrayList<>();
    /** Continue Watching: selected from keep_json_url, drawn in its own row (never in the hero). */
    private final List<Feed.Item> cwItems = new ArrayList<>();
    private final List<View> heroDotsView = new ArrayList<>();
    private int heroCur;
    private boolean heroShown;
    private String heroSig = "";
    private final Runnable heroTick = this::advanceHero;

    /**
     * The backdrop: a full-bleed frame that replaces the wallpaper while an item is showing. Its
     * copy is confined to the top 290dp, so the dock and the row below never move for it.
     */
    private View buildHero() {
        FrameLayout art = new FrameLayout(this);
        art.setVisibility(View.GONE);
        art.setFocusable(true);
        art.setClickable(true);
        art.setId(View.generateViewId());
        heroArt = art;
        heroTarget = art;

        heroStack = new FrameLayout(this);
        art.addView(heroStack, new FrameLayout.LayoutParams(-1, -1));

        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        heroDots.setGravity(Gravity.CENTER_VERTICAL);
        heroDots.setAlpha(0.6f);
        heroDots.setClipChildren(false);

        FrameLayout copy = new FrameLayout(this);
        art.addView(copy, new FrameLayout.LayoutParams(-1, dp(HERO_COPY_DP)));
        copy.addView(heroDots, lp(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, dp(48), dp(24)));

        art.setOnClickListener(v -> openHero(heroCur));
        art.setOnFocusChangeListener((v, has) -> {
            heroStack.animate().scaleX(has ? 1.012f : 1f).scaleY(has ? 1.012f : 1f)
                    .setDuration(has ? 200 : 300).start();
            heroDots.setAlpha(has ? 1f : 0.6f);
            // Focus is the pause: OK must open exactly what is on screen.
            handler.removeCallbacks(heroTick);
            if (!has) handler.postDelayed(heroTick, 1500);
        });
        return art;
    }

    /**
     * The hero is live now and new on Plex only. What you are part-way through used to be mixed in
     * here, which put it five frames into a minute-long rotation; it has its own row now.
     */
    private void fetchHero() {
        final String newUrl = getString(R.string.new_json_url);
        if (newUrl.isEmpty()) return;
        new Thread(() -> {
            List<Feed.Item> items = Feed.hero(Feed.parse(Net.text(newUrl, 4000, 4000, Net.MAX_JSON_BYTES)));
            if (items.isEmpty()) {
                runOnUiThread(this::hideHero);
                return;
            }
            StringBuilder sig = new StringBuilder();
            for (Feed.Item it : items) {
                sig.append(it.title).append('|')
                        .append(it.image).append('|')
                        .append(it.until).append('|')
                        .append(it.progress).append('|');
            }
            final String sigNow = sig.toString();
            // Same list as the one on screen: rebuilding would restart the crossfade and download
            // every still again.
            if (heroTarget != null && sigNow.equals(heroSig)) return;
            final List<Bitmap> imgs = new ArrayList<>(), logos = new ArrayList<>();
            for (Feed.Item it : items) {
                imgs.add(it.image.isEmpty() ? null : Net.image(it.image, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
                logos.add(it.logo.isEmpty() ? null : Net.image(it.logo, 4000, 4000, 1, Net.MAX_PANEL_PIXELS));
            }
            runOnUiThread(() -> showHero(items, imgs, logos, sigNow));
        }).start();
    }

    /** Reads the resume feed. Kept apart from the hero so it can never be crowded out of it again. */
    private void fetchKeep() {
        final String keepUrl = getString(R.string.keep_json_url);
        if (keepUrl.isEmpty()) return;
        new Thread(() -> {
            List<Feed.Item> items = Feed.continueWatching(
                    Feed.parse(Net.text(keepUrl, 4000, 4000, Net.MAX_JSON_BYTES)));
            runOnUiThread(() -> showContinue(items));
        }).start();
    }

    private void showHero(List<Feed.Item> items, List<Bitmap> imgs, List<Bitmap> logos, String sig) {
        boolean hadFocus = heroTarget != null && heroTarget.isFocused();
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
        updateHeroDots();   // the first frame's dot is drawn before any advance happens
        heroArt.setVisibility(View.VISIBLE);
        if (!heroShown) {
            heroShown = true;
            heroArt.setAlpha(0f);
            heroArt.animate().alpha(1f)
                    .setInterpolator(new DecelerateInterpolator(2f)).setDuration(420).start();
        }
        if (hadFocus) heroTarget.requestFocus();
        rewireHero();
        if (!heroLayers.isEmpty() && !heroArt.isFocused()) zoomHeroLayer(heroLayers.get(0));
        startHeroRotation();
    }

    private LinearLayout.LayoutParams dotParams(boolean active) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(active ? 8 : 6), dp(active ? 8 : 6));
        p.setMargins(0, 0, dp(8), 0);
        return p;
    }

    /** One full-bleed frame: art, the two gradients that make text readable, and the copy. */
    private View heroLayer(Feed.Item it, Bitmap bm, Bitmap logo) {
        FrameLayout layer = new FrameLayout(this);
        boolean live = it.live();

        if (bm != null && !it.artfit) {
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(bm);
            layer.setTag(art);   // the one view the slow push animates
            layer.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    live ? new int[]{darken(0xFFD93A32, 0.5f), 0xFF0B0C10}
                            : new int[]{0xFF2A2E38, 0xFF0B0C10}));
            if (bm != null) {   // a channel logo, not a still: show the whole mark, never a crop
                ImageView mark = new ImageView(this);
                mark.setImageBitmap(bm);
                mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
                mark.setAlpha(0.94f);
                layer.addView(mark, lp(dp(300), dp(170), Gravity.END | Gravity.CENTER_VERTICAL,
                        0, 0, dp(120), 0));
            }
        }

        // The top bar sits on the art, so it gets its own shade: 65% at the edge, gone by 150dp.
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6000000, 0x33000000, 0x00000000}));
        layer.addView(top, new FrameLayout.LayoutParams(-1, dp(150)));

        // Black 70% at the left, gone by the middle: the title sits on its own shadow, the art survives.
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xC0000000, 0x5E000000, 0x00000000}));
        layer.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x1A000000, 0x8C000000, 0xF0000000}));
        layer.addView(bottom, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout copy = new FrameLayout(this);
        layer.addView(copy, new FrameLayout.LayoutParams(-1, dp(HERO_COPY_DP)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        SpannableStringBuilder badge = new SpannableStringBuilder();
        if (live) {
            badge.append("\u25CF ");
            badge.setSpan(new ForegroundColorSpan(0xFFFF453A), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            badge.append("LIVE");
            if (!it.channel.isEmpty()) badge.append("  \u00B7  ").append(it.channel);
        } else {
            badge.append("NEW ON PLEX");
        }
        TextView chip = text(18, "sans-serif-medium", Color.WHITE);
        chip.setText(badge);
        if (live) chip.setLetterSpacing(0.06f);   // the one tracked label this screen allows
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(8));
        pill.setColor(0xB3000000);
        chip.setBackground(pill);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        info.addView(chip, new LinearLayout.LayoutParams(-2, -2));

        if (logo != null) {
            ImageView lv = new ImageView(this);
            lv.setImageBitmap(logo);
            lv.setAdjustViewBounds(true);
            lv.setScaleType(ImageView.ScaleType.FIT_START);
            lv.setMaxHeight(dp(56));
            lv.setMaxWidth(dp(420));
            lv.setContentDescription(it.title);
            LinearLayout.LayoutParams lvp = new LinearLayout.LayoutParams(-2, -2);
            lvp.setMargins(0, dp(10), 0, 0);
            info.addView(lv, lvp);
        } else {
            TextView title = text(48, "sans-serif-semibold", Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setText(it.title);
            title.setShadowLayer(dp(12), 0, dp(2), 0xB3000000);
            LinearLayout.LayoutParams tvp = new LinearLayout.LayoutParams(-2, -2);
            tvp.setMargins(0, dp(6), 0, 0);
            info.addView(title, tvp);
        }

        TextView meta = text(20, "sans-serif", 0xD9FFFFFF);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setText(heroMeta(it));
        meta.setShadowLayer(dp(10), 0, dp(1), 0x99000000);
        if (meta.length() > 0) {
            LinearLayout.LayoutParams mvp = new LinearLayout.LayoutParams(-2, -2);
            mvp.setMargins(0, dp(6), 0, 0);
            info.addView(meta, mvp);
        }

        if (it.progress > 0) {
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
                    Math.max(dp(6), (int) (dp(320) * Math.min(1.0, it.progress))), dp(3)));
            LinearLayout.LayoutParams pvp = new LinearLayout.LayoutParams(dp(320), dp(3));
            pvp.setMargins(0, dp(12), 0, 0);
            info.addView(track, pvp);
        }

        copy.addView(info, lp(-1, -2, Gravity.BOTTOM | Gravity.START, dp(48), 0, dp(260), dp(18)));
        return layer;
    }

    /** The one line under the title: when it ends, or what it is. */
    private String heroMeta(Feed.Item it) {
        if (it.live()) {
            return it.until > 0 ? "Ends " + Times.clockTime(it.until,
                    android.text.format.DateFormat.is24HourFormat(this),
                    java.time.ZoneId.systemDefault()) : "";
        }
        return it.sub;
    }

    private void advanceHero() {
        handler.removeCallbacks(heroTick);
        if (heroLayers.size() < 2 || heroTarget == null || heroTarget.isFocused()) return;
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
        if (heroTarget != null && !heroTarget.isFocused()) handler.postDelayed(heroTick, HERO_EVERY_MS);
    }

    private void showHeroFrame(int next) {
        View in = heroLayers.get(next), out = heroLayers.get(heroCur);
        in.animate().cancel();
        out.animate().cancel();
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(HERO_FADE_MS).start();
        out.animate().alpha(0f).setDuration(HERO_FADE_MS).start();
        stopHeroZoom(out);
        zoomHeroLayer(in);
        heroCur = next;
        updateHeroDots();
    }

    /** The slow push while a frame is up: motion, without a jump. Text is never scaled with it. */
    private void zoomHeroLayer(View layer) {
        if (!(layer.getTag() instanceof ImageView)) return;
        ImageView art = (ImageView) layer.getTag();
        art.animate().cancel();
        art.setScaleX(1f);
        art.setScaleY(1f);
        art.animate().scaleX(1.05f).scaleY(1.05f)
                .setInterpolator(new android.view.animation.LinearInterpolator())
                .setDuration(HERO_EVERY_MS + HERO_FADE_MS).start();
    }

    private void stopHeroZoom(View layer) {
        if (!(layer.getTag() instanceof ImageView)) return;
        ImageView art = (ImageView) layer.getTag();
        art.animate().cancel();
        art.setScaleX(1f);
        art.setScaleY(1f);
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

    private boolean expired(Feed.Item it) {
        return it.until > 0 && it.until <= System.currentTimeMillis() / 1000L;
    }

    private void startHeroRotation() {
        handler.removeCallbacks(heroTick);
        if (heroLayers.size() > 1 && (heroTarget == null || !heroTarget.isFocused()))
            handler.postDelayed(heroTick, HERO_EVERY_MS);
    }

    private void hideHero() {
        heroSig = "";
        heroItems.clear();
        heroLayers.clear();
        heroDotsView.clear();
        if (heroArt != null) heroArt.setVisibility(View.GONE);
        handler.removeCallbacks(heroTick);
    }

    private void openHero(int i) {
        if (i < 0 || i >= heroItems.size()) return;
        Feed.Item it = heroItems.get(i);
        if (expired(it)) return;
        openItem(it);
    }

    /** Opens a Plex item's own URI when the app accepts it, otherwise the app itself. */
    private void openItem(Feed.Item it) {
        if (!it.uri.isEmpty()) {
            Intent in = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.uri));
            if (!it.pkg.isEmpty()) in.setPackage(it.pkg);
            if (launch(in)) return;
        }
        if (!it.pkg.isEmpty()) openPackage(it.pkg);
    }

    /** The backdrop sits above the dock: Up from the dock lands on it, Down returns. */
    private void rewireHero() {
        if (heroTarget == null) return;
        View up = pills.isEmpty() ? null : pills.get(0);
        heroTarget.setNextFocusUpId(up == null ? heroTarget.getId() : up.getId());
        heroTarget.setNextFocusDownId(dockCards.isEmpty() ? heroTarget.getId() : dockCards.get(0).getId());
    }

    // ---------------------------------------------------------------- continue watching

    /** The row under the dock: what is part-watched, side-scrolling, never mixed into the hero. */
    private View buildContinue() {
        cwBlock = new LinearLayout(this);
        cwBlock.setOrientation(LinearLayout.VERTICAL);
        cwBlock.setClipChildren(false);
        cwBlock.setVisibility(View.GONE);

        TextView head = text(22, "sans-serif-semibold", 0xE6FFFFFF);
        head.setText("Continue Watching");
        cwBlock.addView(head, llp(-2, -2, dp(48), 0, 0, dp(10)));

        cwScroll = new android.widget.HorizontalScrollView(this);
        cwScroll.setHorizontalScrollBarEnabled(false);
        cwScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        cwScroll.setClipToPadding(false);
        cwScroll.setPadding(dp(48), 0, dp(48), 0);
        cwScroll.setFocusable(false);
        cwScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        cwRow = new LinearLayout(this);
        cwRow.setOrientation(LinearLayout.HORIZONTAL);
        cwRow.setClipChildren(false);
        cwScroll.addView(cwRow, new FrameLayout.LayoutParams(-2, -2));
        cwBlock.addView(cwScroll, llp(-1, -2, 0, 0, 0, 0));
        return cwBlock;
    }

    /** Rebuilds only when the list really changed: every rebuild downloads its artwork again. */
    private void showContinue(List<Feed.Item> items) {
        cwItems.clear();
        cwItems.addAll(items);
        if (items.isEmpty()) {
            cwRow.removeAllViews();
            cwCards.clear();
            cwSig = "";
            cwBlock.setVisibility(View.GONE);
            wireFocus();
            return;
        }
        StringBuilder sig = new StringBuilder();
        for (Feed.Item it : items) {
            sig.append(it.title).append('|').append(it.sub).append('|')
                    .append(it.image).append('|').append(it.progress).append('|');
        }
        if (sig.toString().equals(cwSig)) return;
        cwSig = sig.toString();
        cwRow.removeAllViews();
        cwCards.clear();
        for (int i = 0; i < items.size(); i++) {
            View tile = cwTile(items.get(i));
            // 16:9, not the image's own height: a MATCH_PARENT image inside a wrap-content frame
            // measures as tall as its source and drags the whole home screen up with it.
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(184), dp(104));
            p.setMargins(i == 0 ? 0 : dp(12), 0, 0, 0);
            cwRow.addView(tile, p);
            cwCards.add(tile);
        }
        cwBlock.setVisibility(View.VISIBLE);
        wireFocus();
    }

    /** A 16:9 resume tile: backdrop, title, the one metadata line, and how far through. */
    private View cwTile(Feed.Item it) {
        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(it);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });
        card.setBackgroundColor(0xFF14161C);

        if (!it.image.isEmpty()) {
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
            loadArt(art, it.image);
        } else if (!it.logo.isEmpty()) {   // no still: the show's own mark, contained
            ImageView mark = new ImageView(this);
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mark.setPadding(dp(28), dp(12), dp(28), dp(36));
            mark.setAlpha(0.92f);
            card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2A2E38, 0xFF101116}));
            card.addView(mark, new FrameLayout.LayoutParams(-1, -1));
            loadArt(mark, it.logo);
        } else {
            card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{darken(0xFFE5A00D, 0.4f), 0xFF14161C}));
        }

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x59000000, 0xD9000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(18, "sans-serif-semibold", Color.WHITE);
        title.setText(it.title);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lines.addView(title);
        TextView meta = text(18, "sans-serif", 0xC7FFFFFF);
        meta.setText(it.sub);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lines.addView(meta);
        card.addView(lines, lp(-1, -2, Gravity.BOTTOM, dp(10), 0, dp(10), dp(10)));

        if (it.progress > 0) {
            View groove = new View(this);
            GradientDrawable gg = new GradientDrawable();
            gg.setColor(0x4DFFFFFF);
            groove.setBackground(gg);
            card.addView(groove, lp(-1, dp(3), Gravity.BOTTOM, 0, 0, 0, 0));
            View bar = new View(this);
            GradientDrawable fb = new GradientDrawable();
            fb.setColor(0xFFFFFFFF);
            bar.setBackground(fb);
            card.addView(bar, lp(Math.max(dp(4), (int) (dp(184) * Math.min(1.0, it.progress))), dp(3),
                    Gravity.BOTTOM | Gravity.START, 0, 0, 0, 0));
        }

        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(dp(12));
        rim.setStroke(dp(1), 0x33FFFFFF);
        View edge = new View(this);
        edge.setBackground(rim);
        card.addView(edge, new FrameLayout.LayoutParams(-1, -1));

        card.setOnFocusChangeListener((v, has) -> {
            rim.setStroke(dp(has ? 2 : 1), has ? 0xE6FFFFFF : 0x33FFFFFF);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .translationZ(has ? dp(8) : 0).setDuration(has ? 170 : 200).start();
            if (has) {
                lastFocusedId = v.getId();
                cwScroll.smoothScrollTo(Math.max(0, v.getLeft() - dp(48)), 0);
            }
        });
        card.setOnClickListener(v -> openItem(it));
        return card;
    }

    /** Artwork arrives off the main thread; a missing image costs the picture, not the row. */
    private void loadArt(ImageView into, String url) {
        if (url.isEmpty()) return;
        new Thread(() -> {
            final Bitmap bm = Net.image(url, 4000, 4000, 1, Net.MAX_PANEL_PIXELS);
            if (bm != null) runOnUiThread(() -> into.setImageBitmap(bm));
        }).start();
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

    private static final int HA_PANEL_W_DP = 336;   // 35% of a 960dp screen
    private boolean haOpen;
    private View haScrim;

    /** The detail panel: a glass sheet that slides in from the right, over the home screen. */
    private View buildHaPanel() {
        haPanel = new LinearLayout(this);
        haPanel.setOrientation(LinearLayout.VERTICAL);
        haPanel.setPadding(dp(24), dp(20), dp(24), dp(20));
        haFrost = new Frost(haPanel, dp(24), Math.max(1, dp(1)));
        haPanel.setBackground(haFrost);
        haPanel.setElevation(dp(18));
        haPanel.setFocusable(true);
        haPanel.setClickable(true);
        haPanel.setId(View.generateViewId());
        haPanel.setVisibility(View.GONE);
        haPanel.setOnClickListener(v -> closeHa());

        haBig = text(48, "sans-serif-light", Color.WHITE);   // the screen's ceiling: no bigger than the hero
        Typo.numbers(haBig);
        haPanel.addView(haBig);

        haSub = text(20, "sans-serif-medium", 0xE6FFFFFF);
        haSub.setSingleLine(true);
        haSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        haPanel.addView(haSub);

        haFeels = text(18, "sans-serif", HA_LABEL);
        haFeels.setSingleLine(true);
        haPanel.addView(haFeels, llp(-2, -2, 0, dp(2), 0, 0));

        View rule = new View(this);
        rule.setBackgroundColor(0x1FFFFFFF);
        haPanel.addView(rule, llp(-1, dp(1), 0, dp(14), 0, dp(14)));

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // Bins carries "General · Tomorrow", so it gets the room; AC only ever says "Off".
            float[] weights = r == 0 ? new float[]{1f, 1f, 1f} : new float[]{0.85f, 0.6f, 1.55f};
            for (int c = 0; c < 3; c++) {
                View tile = haTile(r * 3 + c);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, weights[c]);
                p.setMargins(c == 0 ? 0 : dp(8), 0, 0, 0);
                row.addView(tile, p);
            }
            haPanel.addView(row, llp(-1, -2, 0, 0, 0, r == 0 ? dp(14) : 0));
        }

        haRain = text(18, "sans-serif", 0xF2FFFFFF);
        haRainRow = haLine(R.drawable.ic_rain, haRain);
        haPanel.addView(haRainRow, llp(-1, -2, 0, dp(16), 0, 0));
        haNext = text(18, "sans-serif", 0xF2FFFFFF);
        haNextRow = haLine(R.drawable.ic_calendar, haNext);
        haPanel.addView(haNextRow, llp(-1, -2, 0, dp(6), 0, 0));
        return haPanel;
    }

    /** One cell of the sheet: icon over value over label, so three fit in 35% of the screen. */
    private View haTile(int i) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(HA_ICONS[i]);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(HA_LABEL));
        box.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView value = text(20, "sans-serif-semibold", Color.WHITE);
        value.setGravity(Gravity.CENTER);
        value.setMaxLines(2);   // "General \u00b7 Tomorrow" wraps instead of being cut
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Typo.numbers(value);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.setMargins(0, dp(4), 0, 0);
        box.addView(value, vp);
        TextView label = text(18, "sans-serif", HA_LABEL);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setText(HA_LABELS[i]);
        box.addView(label, new LinearLayout.LayoutParams(-1, -2));
        haTileBox[i] = box;
        haTileValue[i] = value;
        haTileLabel[i] = label;
        haTileIcon[i] = icon;
        return box;
    }

    /** OK on the glance strip opens the sheet; it takes focus so the D-pad cannot wander behind it. */
    private void openHa() {
        if (haPanel == null || haOpen) return;
        haOpen = true;
        handler.removeCallbacks(heroTick);
        haScrim.setVisibility(View.VISIBLE);
        haScrim.animate().alpha(1f).setDuration(240).start();
        haPanel.setVisibility(View.VISIBLE);
        haPanel.setTranslationX(dp(HA_PANEL_W_DP));
        haPanel.setAlpha(0f);
        haPanel.animate().translationX(0).alpha(1f)
                .setInterpolator(new DecelerateInterpolator(2f)).setDuration(260).start();
        haPanel.requestFocus();
    }

    private void closeHa() {
        if (!haOpen) return;
        haOpen = false;
        haPanel.animate().translationX(dp(HA_PANEL_W_DP)).alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    if (!haOpen) haPanel.setVisibility(View.GONE);
                }).start();
        haScrim.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (!haOpen) haScrim.setVisibility(View.GONE);
        }).start();
        if (haGlance != null) haGlance.requestFocus();
        startHeroRotation();
    }

    private View haLine(int iconRes, TextView t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(HA_LABEL));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        t.setMaxLines(2);   // a calendar line is long by nature; two lines beat "Working · i..."
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

    /** One glance slot: hidden until it has a value, so the strip never shows an empty icon. */
    private void setGlance(int i, String value, boolean alert) {
        boolean show = !value.isEmpty();
        glanceBox[i].setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        glanceValue[i].setText(value);
        glanceValue[i].setTextColor(alert ? HA_ALERT : Color.WHITE);
    }

    private void showHa(JSONObject j) {
        long now = System.currentTimeMillis() / 1000L;
        JSONObject ages = j.optJSONObject("ages");

        // Four values at the top of the screen: the panel is for detail, not for the basics.
        String gTemp = j.optString("temp", "");
        String gSolar = j.optString("solar", "");
        String gBattery = j.optString("battery", "");
        String gBins = j.optString("bins", "");
        boolean binsDue = !gBins.isEmpty() && !"BINS".equals(binsWhen(gBins));
        setGlance(0, gTemp.isEmpty() ? "" : gTemp + "\u00B0", false);
        setGlance(1, gSolar.isEmpty() ? "" : Times.kw(gSolar) + " kW", false);
        setGlance(2, gBattery.isEmpty() ? "" : gBattery + "%", false);
        setGlance(3, binsDue ? "Bins" : "", binsDue);
        boolean any = false;
        for (LinearLayout b : glanceBox) if (b != null && b.getVisibility() == View.VISIBLE) any = true;
        if (haGlance != null) haGlance.setVisibility(any ? View.VISIBLE : View.GONE);

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
        String due = binsWhen(bins);
        String binsValue = bins.isEmpty() ? "" : binsKind(bins);
        if (!bins.isEmpty() && !"BINS".equals(due)) binsValue += " \u00B7 " + capitalise(due);
        setHaTile(5, binsValue, bins, ages, HA_LABELS[5]);
        alertTile(5, !bins.isEmpty() && !"BINS".equals(due));

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

    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);

        // Sentence case, 70% white: a person greeting you, not a label shouting at you.
        greeting = text(20, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setSingleLine(true);
        greeting.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(greeting, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);

        // Four values at a glance. One focus target: OK opens the detail panel.
        haGlance = new LinearLayout(this);
        haGlance.setOrientation(LinearLayout.HORIZONTAL);
        haGlance.setGravity(Gravity.CENTER_VERTICAL);
        haGlance.setFocusable(true);
        haGlance.setId(View.generateViewId());
        haGlance.setPadding(dp(6), dp(4), dp(6), dp(4));
        haGlance.setVisibility(View.GONE);   // until the first snapshot arrives
        haGlance.setOnClickListener(v -> openHa());
        GradientDrawable gbg = new GradientDrawable();
        gbg.setCornerRadius(dp(10));
        gbg.setColor(Color.TRANSPARENT);
        haGlance.setBackground(gbg);
        haGlance.setOnFocusChangeListener((v, has) -> {
            gbg.setColor(has ? 0x1FFFFFFF : Color.TRANSPARENT);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(170).start();
        });
        int[] glanceIcons = {R.drawable.ic_thermo, R.drawable.ic_solar,
                R.drawable.ic_battery, R.drawable.ic_bins};
        for (int i = 0; i < 4; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(this);
            icon.setImageResource(glanceIcons[i]);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(0x99FFFFFF));
            item.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView value = text(20, "sans-serif-medium", Color.WHITE);
            value.setSingleLine(true);
            Typo.numbers(value);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
            vp.setMargins(dp(6), 0, 0, 0);
            item.addView(value, vp);
            item.setVisibility(View.GONE);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-2, -2);
            ip.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            haGlance.addView(item, ip);
            glanceBox[i] = item;
            glanceValue[i] = value;
        }
        right.addView(haGlance, llp(-2, dp(44), 0, 0, 0, 0));
        pills.add(haGlance);

        clock = text(28, "sans-serif-light", Color.WHITE);
        Typo.numbers(clock);
        clock.setIncludeFontPadding(false);
        right.addView(clock, llp(-2, -2, dp(6), 0, 0, 0));

        if (hasPassthroughInputs()) {   // HDMI-in devices only (Fire TV Cube, TVs); sticks have none
            inputsChip = chip(R.drawable.ic_input, "Inputs", v -> showInputs(v));
            right.addView(inputsChip, llp(-2, dp(40), dp(10), 0, 0, 0));
            pills.add(inputsChip);
        }
        wifiPill = chip(R.drawable.ic_wifi, "Wi-Fi", v -> openWifiSettings());
        setNet("Wi-Fi", false);
        right.addView(wifiPill, llp(-2, dp(40), dp(10), 0, 0, 0));
        pills.add(wifiPill);

        settingsPill = chip(R.drawable.ic_settings, "Settings", v -> openFullSettings());
        settingsPill.setOnLongClickListener(v -> {
            getSharedPreferences("launcher", MODE_PRIVATE).edit().remove("hidden").remove("dock_order").apply();
            android.widget.Toast.makeText(this, "Hidden apps and main row reset", android.widget.Toast.LENGTH_SHORT).show();
            buildApps();
            return true;
        });
        right.addView(settingsPill, llp(-2, dp(40), dp(10), 0, 0, 0));
        pills.add(settingsPill);

        bar.addView(right, new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    /**
     * An icon-only chip. Its label appears while it has focus, so the bar stays quiet at rest but
     * a focused chip still says what it does - and the network one can say "Link 1201 Mbps".
     */
    private TextView chip(int iconRes, String label, View.OnClickListener click) {
        TextView p = text(18, "sans-serif-medium", 0xE6FFFFFF);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(6), 0, dp(6), 0);
        p.setMinWidth(dp(30));
        p.setFocusable(true);
        p.setId(View.generateViewId());
        p.setTag(label);
        android.graphics.drawable.Drawable ic = getDrawable(iconRes);
        ic.setBounds(0, 0, dp(18), dp(18));
        p.setCompoundDrawables(ic, null, null, null);
        p.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(Color.TRANSPARENT);
        p.setBackground(bg);
        p.setOnClickListener(click);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x26FFFFFF : Color.TRANSPARENT);
            p.setText(has ? String.valueOf(v.getTag()) : "");
            p.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(has ? 0xFFFFFFFF : 0xE6FFFFFF));
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(180).start();
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
        int d = dockCards.size(), g = gridCards.size(), c = cwCards.size();
        boolean hasCw = c > 0 && cwBlock != null && cwBlock.getVisibility() == View.VISIBLE;
        boolean hasHero = heroArt != null && heroArt.getVisibility() == View.VISIBLE;
        int topId = pills.isEmpty() ? View.NO_ID : pills.get(0).getId();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        for (int i = 0; i < d; i++) {
            View card = dockCards.get(i);
            card.setNextFocusUpId(hasHero ? heroTarget.getId() : topId);
            card.setNextFocusLeftId(i == 0 ? card.getId() : dockCards.get(i - 1).getId());
            card.setNextFocusRightId(i == d - 1 ? card.getId() : dockCards.get(i + 1).getId());
            card.setNextFocusDownId(hasCw ? cwCards.get(Math.min(i, c - 1)).getId()
                    : g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : card.getId());
        }
        for (int i = 0; i < c; i++) {
            View tile = cwCards.get(i);
            tile.setNextFocusUpId(d > 0 ? dockCards.get(Math.min(i, d - 1)).getId() : topId);
            tile.setNextFocusLeftId(i == 0 ? tile.getId() : cwCards.get(i - 1).getId());
            tile.setNextFocusRightId(i == c - 1 ? tile.getId() : cwCards.get(i + 1).getId());
            tile.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : tile.getId());
        }
        rewireHero();
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View card = gridCards.get(j);
            int r = j / cols, col = j % cols;
            card.setNextFocusUpId(r == 0
                    ? (hasCw ? cwCards.get(Math.min(col, c - 1)).getId()
                       : d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            card.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : card.getId());
            card.setNextFocusLeftId(col == 0 ? card.getId() : gridCards.get(j - 1).getId());
            card.setNextFocusRightId(col == cols - 1 || j == g - 1 ? card.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void focusNow(View v) {
        if (v != null) v.requestFocus();
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the home screen up just enough to show it under the dock;
        // later rows centre on screen.
        int target = grid.indexOfChild(row) == 0 ? y - dp(250) : y - (int) (screenH * 0.42f);
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
        String g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
        String full = familyName.isEmpty() ? g : g + ", " + familyName;
        greeting.setText(full);
        // On a narrower top bar the name is dropped cleanly rather than cut mid-word.
        greeting.post(() -> {
            if (familyName.isEmpty()) return;
            android.text.Layout l = greeting.getLayout();
            if (l != null && l.getEllipsisCount(0) > 0) greeting.setText(g);
        });
        timeTint.setBackgroundColor(h >= 18 || h < 5 ? 0x26FF7A2E : h < 10 ? 0x1A7FB8FF : 0x00000000);
        // One formatter for every time on this screen, in the device's own zone.
        clock.setText(Times.clockTime(now.getTimeInMillis() / 1000L,
                android.text.format.DateFormat.is24HourFormat(this), java.time.ZoneId.systemDefault()));
    }

    private void setNet(String label, boolean ethernet) {
        netLabel = label;
        wifiPill.setTag(label);
        android.graphics.drawable.Drawable ic = getDrawable(ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi);
        int sz = dp(18);
        ic.setBounds(0, 0, sz, sz);
        wifiPill.setCompoundDrawables(ic, null, null, null);
        wifiPill.setCompoundDrawablePadding(0);
        if (wifiPill.isFocused()) wifiPill.setText(label);   // the chip is icon-only at rest
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
        topBar.setAlpha(0f);
        topBar.setTranslationY(-dp(12));
        topBar.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
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
        if (e.getAction() == KeyEvent.ACTION_DOWN && heroTarget != null && heroArt.getVisibility() == View.VISIBLE) {
            View now = getCurrentFocus();
            if (dockCards.contains(now) && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                heroTarget.requestFocus();
                return true;
            }
            if (now == heroTarget) {
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
