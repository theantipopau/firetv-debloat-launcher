package com.mihir.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
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
import android.graphics.Paint;
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
import android.view.ViewTreeObserver;
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
 * The minimalist home screen: the clock and a few plain lines top-left, two words top-right, one
 * Now Showing card, and a single glass pill of app icons at the bottom. Down from the pill reveals
 * Continue Watching; every other app lives in the grid below the fold.
 * Plain framework views only (no AndroidX), no bundled brand art.
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
        /** Banner when the app ships one; the square icon lives in {@code icon} for the dock. */
        Drawable art, icon;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockPillRow;
    /** The one screen of home: clock block, Now Showing, the dock pill and the hidden row. */
    private FrameLayout page;
    private LinearLayout grid;
    private View bottomWash, cwWash, gridShade;
    private ImageView wallBack, wallFront;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView clock, greet, dateLine, appsLabel, statusWeather, statusBattery, statusSolar, statusAc, statusBins;
    private LinearLayout statusBlock, statusLineBox;
    private View statusWeatherRow, statusBatteryRow, statusAcRow, statusBinsRow;
    private LinearLayout statusSolarGroup;
    private ImageView statusBatteryIcon;
    private View topActions;
    private View actionApps, actionSettings;
    /** Wi-Fi or Ethernet, back where it always was: icon at rest, the network's name on focus. */
    private TextView netChip;
    /** The glass colour, tinted by the wallpaper: the dock, the cards and the round buttons share it. */
    private int glassColor = HomeTheme.GLASS;
    private final List<GradientDrawable> glasses = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    /** The dock as drawn, so the cover can paint the same icons without a package lookup. */
    static volatile List<App> dockSnapshot;
    private String netLabel = "Wi-Fi";
    /** Optional `eth_mbps` from the HA snapshot: only used when the TV cannot read its own rate. */
    private String haEthMbps = "";
    private String cwSig = "";
    private ValueAnimator scrollGlide;
    private boolean haShown;
    /** The photo on screen right now, so the cover borrows it instead of decoding (and showing) an older one. */
    static volatile Bitmap currentWall;
    /** True while this window is in front: the Home key then needs no cover to hide Amazon's home. */
    static volatile boolean visible;
    private int lastFocusedId = View.NO_ID;
    /** The viewer has pressed a direction/OK: from then on the cursor is theirs, never moved for them. */
    private boolean userNavigated;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    // ---- Home Assistant snapshot panel (JSON written by an HA automation, polled over the LAN)
    static volatile long suppressRedirectUntil;
    private LinearLayout haPanel;
    /** The snapshot on screen; the settings sheet re-applies it when a preference changes. */
    private JSONObject lastHa;
    private Frost haFrost;
    private TextView haBig, haSub, haWeather, haSun, haSolar, haRain, haNext, haPeople;
    private View haWeatherRow, haSunRow, haSolarRow, haRainRow, haNextRow, haPeopleRow;
    private ImageView haSolarIcon;
    // Six tiles in two rows of three: solar, battery, house load / inside, AC, bins.
    private static final String[] HA_KEYS = {"solar", "battery", "house", "inside", "ac", "bins"};
    private static final String[] HA_LABELS = {"Solar", "Battery", "Home", "Inside", "AC", "Bins"};
    private static final int[] HA_ICONS = {R.drawable.ic_solar, R.drawable.ic_battery, R.drawable.ic_house,
            R.drawable.ic_thermo, R.drawable.ic_ac, R.drawable.ic_bins};
    private final View[] haTileBox = new View[6];
    private final TextView[] haTileValue = new TextView[6];
    private final TextView[] haTileLabel = new TextView[6];
    private final ImageView[] haTileIcon = new ImageView[6];
    /** A row whose three cells are all empty is taken out of the sheet rather than left as a gap. */
    private final View[] haTileRows = new View[2];
    /** The one accent colour on the home screen, and it only ever means "look at this". */
    private static final int HA_ALERT = 0xFFFFC857;
    private static final int HA_LABEL = 0x99FFFFFF;
    private FrameLayout settingsPage;
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
            if (pollN++ % 20 == 0) { fetchNow(); fetchKeep(); }
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
    protected void attachBaseContext(Context base) {
        // One scale for the whole screen (see HomeTheme.UI_SCALE): every dp and sp follows it.
        android.content.res.Configuration c =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        c.densityDpi = Math.round(c.densityDpi * HomeTheme.UI_SCALE);
        super.attachBaseContext(base.createConfigurationContext(c));
    }

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
        // The Home-key cover has done its job the moment this window paints; wait for the frame
        // rather than onResume, which can run before the cover window is even added.
        final View rootView = getWindow().getDecorView();
        rootView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                rootView.getViewTreeObserver().removeOnPreDrawListener(this);
                HomeKeyService.coverGone();
                return true;
            }
        });

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
        visible = true;
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
        startNowRotation();
        if (pendingFocusPkg != null) {   // keep the cursor on an app we just moved
            for (View c : dockCards) if (((App) c.getTag()).pkg.equals(pendingFocusPkg)) lastFocusedId = c.getId();
            for (View c : gridCards) if (((App) c.getTag()).pkg.equals(pendingFocusPkg)) lastFocusedId = c.getId();
            pendingFocusPkg = null;
        }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        focusNow(last);
        // Twice: once before the backdrop has loaded and once after, since a cold start can hand
        // the backdrop the first focusable slot and leave the cursor above the dock.
        handler.postDelayed(this::settleFirstFocus, 400);
        handler.postDelayed(this::settleFirstFocus, 1500);
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
        getSharedPreferences("tvhome", 0).edit().putBoolean("foreground", false).apply();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(haPoll);
        stopNowRotation();
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
        if (haOpen) closeHa();   // Home must never leave the sheet standing open
        hideContinue();
        hideSettings();
        dimBackdrop(false);
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        glideTo(0);
    }

    @Override
    public void onBackPressed() {
        // Back closes whatever is open, in order of how recently it opened.
        if (haOpen) { closeHa(); return; }
        if (settingsPage != null && settingsPage.getVisibility() == View.VISIBLE) { hideSettings(); return; }
        if (cwOpen) {
            hideContinue();
            if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
            return;
        }
        View now = getCurrentFocus();
        boolean away = scroll.getScrollY() > 0 || gridCards.contains(now) || cwCards.contains(now);
        if (away && !dockCards.isEmpty()) {
            dimBackdrop(false);
            dockCards.get(0).requestFocus();
            glideTo(0);
            return;
        }
        // Nothing else for Back to do: stay on home rather than falling through to Amazon's.
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

        // Two gradients and nothing else: a soft left wash under the text, a floor under the dock.
        View leftWash = new View(this);
        leftWash.setBackground(HomeTheme.leftWash());
        root.addView(leftWash, new FrameLayout.LayoutParams(-1, -1));
        bottomWash = new View(this);
        bottomWash.setBackground(HomeTheme.bottomWash());
        root.addView(bottomWash, lp(-1, dp(340), Gravity.BOTTOM, 0, 0, 0, 0));

        // Only ever visible while the apps grid is in view; at rest it is fully transparent.
        gridShade = new View(this);
        gridShade.setBackgroundColor(0x99000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        // Focus belongs to what is on screen, never to the container that slides it.
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // The home screen: one screen tall. Wallpaper behind, the clock block top-left, the Now
        // Showing card under it, the dock pill at the bottom; the apps grid begins below the fold.
        page = new FrameLayout(this);
        page.setClipChildren(false);
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(page, new LinearLayout.LayoutParams(-1, screenH));

        // One column: the clock and its lines, then the card. Both read left-aligned at 48dp.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        left.setClipToPadding(false);
        left.addView(buildStatusBlock());
        page.addView(left, lp(dp(HomeTheme.NOW_W), -2, Gravity.TOP | Gravity.START,
                dp(HomeTheme.SAFE), dp(HomeTheme.CLOCK_TOP), 0, 0));

        // Sat on the dock's glass, one gutter above it, and stretched between the margins as on the
        // Samsung: anchoring to the bottom keeps the gap whatever the screen's height in dp.
        page.addView(buildNowRow(), lp(-1, -2, Gravity.BOTTOM | Gravity.START,
                dp(HomeTheme.SAFE), 0, dp(HomeTheme.SAFE),
                dp(HomeTheme.DOCK_BOTTOM + HomeTheme.DOCK_PILL_H + HomeTheme.GUTTER)));

        topActions = buildTopActions();
        page.addView(topActions, lp(-2, -2, Gravity.TOP | Gravity.END,
                0, dp(HomeTheme.ACTION_TOP), dp(HomeTheme.SAFE), 0));

        page.addView(buildDockPill(), lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                0, 0, 0, dp(HomeTheme.DOCK_BOTTOM)));

        // The revealed row is drawn over a deeper floor; both animate in together.
        cwWash = new View(this);
        cwWash.setBackground(HomeTheme.revealWash());
        cwWash.setAlpha(0f);
        cwWash.setVisibility(View.GONE);
        page.addView(cwWash, lp(-1, dp(430), Gravity.BOTTOM, 0, 0, 0, 0));
        cwReveal = buildContinue();
        page.addView(cwReveal, lp(-1, -2, Gravity.BOTTOM, 0, 0, 0,
                dp(HomeTheme.DOCK_BOTTOM + HomeTheme.DOCK_PILL_H + HomeTheme.CW_TO_DOCK)));

        appsLabel = text(Typo.SECTION, "sans-serif-semibold", 0xE6FFFFFF);
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
        root.addView(buildHaPanel(), lp(dp(HomeTheme.HA_W), -2, Gravity.END | Gravity.CENTER_VERTICAL,
                0, dp(HomeTheme.HA_MARGIN), dp(HomeTheme.HA_MARGIN), dp(HomeTheme.HA_MARGIN)));

        settingsPage = buildSettingsPage();
        root.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));

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

    // ---------------------------------------------------------------- status block

    /** Clock, weather, batteries, air con and bins: one focus target, and no panel behind it. */
    private View buildStatusBlock() {
        statusBlock = new LinearLayout(this);
        statusBlock.setOrientation(LinearLayout.VERTICAL);
        statusBlock.setFocusable(true);
        statusBlock.setClickable(true);
        statusBlock.setId(View.generateViewId());
        statusBlock.setClipChildren(false);
        statusBlock.setPadding(dp(HomeTheme.BLOCK_PAD_H), dp(HomeTheme.BLOCK_PAD_V),
                dp(HomeTheme.BLOCK_PAD_H), dp(HomeTheme.BLOCK_PAD_BOTTOM));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(HomeTheme.BLOCK_RADIUS));
        bg.setColor(Color.TRANSPARENT);
        // The highlight is the block's own box, padding included: an inset edge used to cross the
        // last line whenever a line was hidden, so the glass ended through "Air con off".
        statusBlock.setBackground(bg);
        // OK on the block opens the detail sheet; the block is the only way in.
        statusBlock.setOnClickListener(v -> openHa());
        statusBlock.setOnFocusChangeListener((v, has) ->
                bg.setColor(has ? 0x14FFFFFF : Color.TRANSPARENT));

        // The greeting sits above the clock, quiet enough to read as a caption on the wallpaper.
        // 18sp at 70%: a caption to the clock, so the time is plainly the first thing read.
        greet = text(18, "sans-serif-medium", HomeTheme.TEXT_70);
        greet.setSingleLine(true);
        greet.setEllipsize(android.text.TextUtils.TruncateAt.END);
        greet.setIncludeFontPadding(false);
        greet.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        statusBlock.addView(greet, llp(-1, -2, 0, 0, 0, dp(HomeTheme.GREET_GAP)));

        clock = text(56, "sans-serif-light", Color.WHITE);
        clock.setLetterSpacing(HomeTheme.CLOCK_TRACKING);
        Typo.numbers(clock);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        statusBlock.addView(clock);
        dateLine = text(18, "sans-serif", 0xB3FFFFFF);
        dateLine.setSingleLine(true);
        dateLine.setIncludeFontPadding(false);
        dateLine.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        statusBlock.addView(dateLine, llp(-1, -2, 0, dp(HomeTheme.DATE_TOP), 0, 0));

        statusLineBox = new LinearLayout(this);
        statusLineBox.setOrientation(LinearLayout.VERTICAL);
        statusLineBox.setClipChildren(false);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1, -2);
        box.topMargin = dp(HomeTheme.CLOCK_GAP);
        statusBlock.addView(statusLineBox, box);

        statusWeather = lineText();
        statusWeatherRow = statusLine(R.drawable.ic_thermo, statusWeather);
        statusBattery = lineText();
        statusBatteryRow = statusLine(R.drawable.ic_battery, statusBattery);
        statusBatteryIcon = (ImageView) ((LinearLayout) statusBatteryRow).getChildAt(0);
        // Producing solar joins the battery's own line: the two numbers belong together, and the
        // block has no room for a fifth row. Hidden at night, so the line is just the battery.
        statusSolar = lineText();
        statusSolarGroup = new LinearLayout(this);
        statusSolarGroup.setOrientation(LinearLayout.HORIZONTAL);
        statusSolarGroup.setGravity(Gravity.CENTER_VERTICAL);
        statusSolarGroup.setVisibility(View.GONE);
        ImageView solarMark = new ImageView(this);
        solarMark.setImageResource(R.drawable.ic_sun);
        solarMark.setImageTintList(android.content.res.ColorStateList.valueOf(HomeTheme.TEXT_70));
        LinearLayout.LayoutParams smp = new LinearLayout.LayoutParams(
                dp(HomeTheme.LINE_ICON), dp(HomeTheme.LINE_ICON));
        smp.leftMargin = dp(14);
        smp.rightMargin = dp(HomeTheme.LINE_ICON_GAP);
        statusSolarGroup.addView(solarMark, smp);
        statusSolarGroup.addView(statusSolar, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout) statusBatteryRow).addView(statusSolarGroup, new LinearLayout.LayoutParams(-2, -2));
        statusAc = lineText();
        statusAcRow = statusLine(R.drawable.ic_ac, statusAc);
        statusBins = lineText();
        statusBinsRow = statusLine(R.drawable.ic_bins, statusBins);
        return statusBlock;
    }

    /** One plain line: a 14dp mark, then the words. Hidden until the snapshot has something to say. */
    private View statusLine(int iconRes, TextView t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(HomeTheme.TEXT_70));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(HomeTheme.LINE_ICON),
                dp(HomeTheme.LINE_ICON));
        ip.rightMargin = dp(HomeTheme.LINE_ICON_GAP);
        row.addView(icon, ip);
        row.addView(t, new LinearLayout.LayoutParams(-2, -2));
        row.setVisibility(View.GONE);
        statusLineBox.addView(row, llp(-1, -2, 0, 0, 0, dp(HomeTheme.LINE_GAP)));
        return row;
    }

    private TextView lineText() {
        TextView t = text(20, "sans-serif", HomeTheme.TEXT_90);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        // No font padding: four lines plus the 96dp card have to clear the pill on a 540dp screen.
        t.setIncludeFontPadding(false);
        Typo.numbers(t);
        return t;
    }

    /** Sets one line's words, its colour and its visibility in one place. */
    private void setStatusLine(View row, TextView value, String text, int color, float alpha) {
        boolean show = text != null && !text.isEmpty();
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        value.setText(text);
        value.setTextColor(color);
        row.setAlpha(alpha);
    }

    private void tintBatteryIcon(int color) {
        if (statusBatteryIcon != null)
            statusBatteryIcon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
    }

    // ---------------------------------------------------------------- top-right actions

    private View buildTopActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        netChip = chip(isEthernet() ? R.drawable.ic_ethernet : R.drawable.ic_wifi,
                () -> netLabel, v -> openWifiSettings());
        row.addView(netChip, llp(-2, dp(HomeTheme.ACTION_SIZE), 0, 0, dp(HomeTheme.ACTION_GAP), 0));
        actionApps = chip(R.drawable.ic_apps, () -> "All apps", v -> showAllApps());
        row.addView(actionApps, llp(-2, dp(HomeTheme.ACTION_SIZE), 0, 0, dp(HomeTheme.ACTION_GAP), 0));
        actionSettings = chip(R.drawable.ic_settings, () -> "Settings", v -> showSettings());
        actionSettings.setOnLongClickListener(v -> {
            getSharedPreferences("launcher", MODE_PRIVATE).edit()
                    .remove("hidden").remove("dock_order").apply();
            android.widget.Toast.makeText(this, "Hidden apps and main row reset",
                    android.widget.Toast.LENGTH_SHORT).show();
            buildApps();
            return true;
        });
        row.addView(actionSettings, llp(-2, dp(HomeTheme.ACTION_SIZE), 0, 0, 0, 0));
        return row;
    }

    /**
     * The top-right controls, drawn the way tvOS draws its own: an icon in a 44dp glass circle at
     * rest, and under the cursor a white capsule with dark ink that says what it does. The network
     * chip's label is the network itself.
     */
    private TextView chip(int iconRes, java.util.function.Supplier<String> label, View.OnClickListener click) {
        final TextView b = text(18, "sans-serif-medium", Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinWidth(dp(HomeTheme.ACTION_SIZE));
        b.setFocusable(true);
        b.setId(View.generateViewId());
        b.setTag(iconRes);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(HomeTheme.ACTION_SIZE / 2));
        bg.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)), HomeTheme.GLASS_EDGE);
        b.setBackground(bg);
        chips.add(b);
        paintChip(b, iconRes, false, "");
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            paintChip(b, (Integer) v.getTag(), has, label.get());
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setInterpolator(has ? HomeTheme.settle() : HomeTheme.ease())
                    .setDuration(HomeTheme.MS_FOCUS).start();
            if (has) lastFocusedId = v.getId();
        });
        return b;
    }

    private void paintChip(TextView chip, int iconRes, boolean focused, String label) {
        Drawable ic = getDrawable(iconRes).mutate();
        ic.setBounds(0, 0, dp(20), dp(20));
        chip.setCompoundDrawables(ic, null, null, null);
        int ink = focused ? 0xFF111114 : 0xF2FFFFFF;
        chip.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(ink));
        chip.setTextColor(ink);
        String text = focused && label != null ? label : "";
        chip.setText(text);
        chip.setCompoundDrawablePadding(text.isEmpty() ? 0 : dp(8));
        chip.setPadding(dp(12), 0, dp(text.isEmpty() ? 12 : 18), 0);
        ((GradientDrawable) chip.getBackground()).setColor(focused ? 0xF2FFFFFF : glassColor);
    }

    // ---------------------------------------------------------------- Now Showing

    /** One card, two layers: a card-sized crossfade, not a blank frame between items. */
    private static final class NowPane {
        FrameLayout root;
        ImageView art, badge;
        View dot, scrim, words;
        TextView source, title, meta;
    }

    private static final long NOW_EVERY_MS = 10000;
    /** One origin for both cards' timers, so their slots stay half a beat apart forever. */
    private static final long NOW_EPOCH = SystemClock.elapsedRealtime();
    private static final long NOW_FADE_MS = HomeTheme.MS_FADE;
    /** How many frames each card rotates: the guide's next few live events, Plex's newest few titles. */
    private static final int LIVE_MAX = 10, PLEX_MAX = 10;
    /** Card art fills the 425x96dp card; the feed's 720x405 stills fit whole, a 1080p one is cut down. */
    private static final long NOW_PIXELS = 300_000;

    /**
     * One rotating card. Home shows two of them side by side - live on the left, new on Plex on the
     * right - so each card owns its list, its artwork and its own timer instead of the screen
     * keeping six sets of fields for one of them.
     */
    private final class Deck {
        final boolean live;
        final FrameLayout root;
        final NowPane[] panes = new NowPane[2];
        final List<Feed.Item> items = new ArrayList<>();
        final List<Bitmap> images = new ArrayList<>(), logos = new ArrayList<>(), badges = new ArrayList<>();
        int front, cur;
        String sig = "";
        final Runnable tick = this::advance;

        Deck(boolean live) {
            this.live = live;
            root = buildDeck(this);
        }

        boolean showing() {
            return root.getVisibility() == View.VISIBLE && !items.isEmpty();
        }

        void advance() {
            handler.removeCallbacks(tick);
            if (items.size() < 2 || root.isFocused()) return;
            // A full-card crossfade landing under a focus move was the slow tenth of frames:
            // while the remote is in use, wait for a pause instead of turning mid-move.
            if (SystemClock.uptimeMillis() - lastKeyAt < KEY_QUIET_MS) {
                handler.postDelayed(tick, KEY_QUIET_MS);
                return;
            }
            int next = nextFrame();
            if (next < 0) return;
            render(next);
            arm();
        }

        /** The next item that has not finished; -1 when every live programme has ended. */
        int nextFrame() {
            int n = items.size();
            for (int i = 1; i < n; i++) {
                int cand = (cur + i) % n;
                if (!expired(items.get(cand))) return cand;
            }
            return -1;
        }

        /** Live turns on the beat, Plex half a beat later: the row never changes both sides at once. */
        long phase() {
            return live ? 0 : NOW_EVERY_MS / 2;
        }

        /**
         * Both cards sit on one 10s grid instead of each running a 10s countdown of its own. A
         * countdown restarts whenever the deck is re-armed - new feed data, focus leaving, the
         * screen waking - and two restarts a moment apart silently put the cards back in step.
         * On a shared grid a re-arm lands on that card's own slot, so live stays at :00 and Plex
         * at :05 however often either is restarted.
         */
        void arm() {
            long now = SystemClock.elapsedRealtime();
            long at = NOW_EPOCH + phase();
            if (at < now) at += ((now - at) / NOW_EVERY_MS + 1) * NOW_EVERY_MS;
            handler.postDelayed(tick, Math.max(150, at - now));
        }

        void start() {
            handler.removeCallbacks(tick);
            if (items.size() > 1 && !root.isFocused()) arm();
        }

        void open() {
            if (cur < 0 || cur >= items.size()) return;
            Feed.Item it = items.get(cur);
            if (expired(it)) return;
            openItem(it);
        }

        /** One card-sized crossfade: two panes, so there is never a blank frame between items. */
        void render(int i) {
            if (i < 0 || i >= items.size()) return;
            int back = 1 - front;
            NowPane p = panes[back], out = panes[front];
            Feed.Item it = items.get(i);
            Bitmap logo = logos.get(i), img = images.get(i);
            Bitmap badge = i < badges.size() ? badges.get(i) : null;
            // A still fills the card; a logo or a transparent mark cannot be cropped, so it is
            // contained at the card's right end, clear of the words, on the plain glass.
            boolean still = img != null && !it.artfit && !img.hasAlpha();
            Bitmap art = still ? img : (logo != null ? logo : img);
            p.art.setImageBitmap(art);
            p.art.setScaleType(still ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            int cardW = root.getWidth() > 0 ? root.getWidth() : dp(HomeTheme.NOW_W);
            int markLeft = cardW - dp(HomeTheme.NOW_MARK_W);
            p.art.setPadding(still ? 0 : markLeft, still ? 0 : dp(14), still ? 0 : dp(16), still ? 0 : dp(14));
            p.scrim.setVisibility(still ? View.VISIBLE : View.GONE);
            // Over a still the words may run on; beside a contained mark they stop short of it.
            FrameLayout.LayoutParams wlp = (FrameLayout.LayoutParams) p.words.getLayoutParams();
            wlp.rightMargin = dp(still ? HomeTheme.NOW_PAD : HomeTheme.NOW_MARK_W);
            p.words.setLayoutParams(wlp);
            p.title.setText(it.title);
            boolean isLive = it.live();
            // No brand on the eyebrow: the dock calls that app Live TV now, and "on TiviMate"
            // only brings back the name that confused the point of it.
            p.source.setText(isLive ? "Live now"
                    : "Now Showing on " + (it.app.isEmpty() ? "Plex" : it.app));
            android.graphics.drawable.Drawable dbg = p.dot.getBackground();
            if (dbg instanceof GradientDrawable)
                ((GradientDrawable) dbg).setColor(isLive ? 0xFFFF453A : 0xFFE5A00D);
            String meta = nowMeta(it);
            // A live card's channel mark stands where the channel's name was: "7AFL \u00B7 until 21:39"
            // becomes the EPG logo and the time. The name is still the fallback when the guide has no icon.
            if (isLive && badge != null) {
                String ch = it.channel;
                if (!ch.isEmpty() && meta.startsWith(ch))
                    meta = meta.substring(ch.length()).replaceFirst("^\\s*\\u00B7\\s*", "\u00B7 ");
                // A mark drawn for a white page disappears on the card's glass; those get a light
                // plate behind them, the same treatment a light logo needs nowhere.
                boolean plate = darkMark(badge);
                int markH = 18;   // inside the 22dp box, 2dp of air above and below
                int markW = Math.max(8, Math.min(104,
                        Math.round(markH * badge.getWidth() / (float) badge.getHeight())));
                LinearLayout.LayoutParams blp =
                        (LinearLayout.LayoutParams) p.badge.getLayoutParams();
                blp.width = dp(markW + (plate ? 8 : 0));
                blp.height = dp(22);
                p.badge.setLayoutParams(blp);
                p.badge.setPadding(dp(plate ? 4 : 0), dp(2), dp(plate ? 4 : 0), dp(2));
                p.badge.setBackground(plate ? markPlate() : null);
                p.badge.setImageBitmap(badge);
                p.badge.setVisibility(View.VISIBLE);
            } else {
                p.badge.setImageDrawable(null);
                p.badge.setVisibility(View.GONE);
            }
            p.meta.setText(meta);
            p.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

            p.root.animate().cancel();
            out.root.animate().cancel();
            p.root.setAlpha(0f);
            // On a layer: a full-bleed still fading over glass is otherwise redrawn every frame.
            p.root.animate().alpha(1f).setDuration(NOW_FADE_MS).withLayer().start();
            out.root.animate().alpha(0f).setDuration(NOW_FADE_MS).withLayer().start();
            front = back;
            cur = i;
        }
    }

    private Deck liveDeck, plexDeck;

    /** Both cards, left to right: whatever is on screen gets the same treatment. */
    private List<Deck> decks() {
        List<Deck> out = new ArrayList<>(2);
        if (liveDeck != null) out.add(liveDeck);
        if (plexDeck != null) out.add(plexDeck);
        return out;
    }

    /** The two cards as one row: evenly spaced either side of the screen's centre line. */
    private View buildNowRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        liveDeck = new Deck(true);
        plexDeck = new Deck(false);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(HomeTheme.NOW_H), 1f);
        lp1.rightMargin = dp(HomeTheme.NOW_GAP_X);
        row.addView(liveDeck.root, lp1);
        row.addView(plexDeck.root, new LinearLayout.LayoutParams(0, dp(HomeTheme.NOW_H), 1f));
        return row;
    }

    private FrameLayout buildDeck(final Deck deck) {
        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setVisibility(View.GONE);
        final GradientDrawable glass = HomeTheme.glassPill(HomeTheme.NOW_RADIUS,
                getResources().getDisplayMetrics().density);
        glasses.add(glass);
        card.setBackground(glass);

        for (int i = 0; i < 2; i++) {
            NowPane p = nowPane();
            p.root.setAlpha(i == 0 ? 1f : 0f);
            deck.panes[i] = p;
            card.addView(p.root, new FrameLayout.LayoutParams(-1, -1));
        }

        // Over the panes, not under them: the artwork fills the card and would hide it.
        final GradientDrawable halo = new GradientDrawable();
        halo.setCornerRadius(dp(HomeTheme.NOW_RADIUS));
        halo.setStroke(dp(2), 0x00FFFFFF);
        View rim = new View(this);
        rim.setBackground(halo);
        card.addView(rim, new FrameLayout.LayoutParams(-1, -1));

        card.setOnClickListener(v -> deck.open());
        card.setOnFocusChangeListener((v, has) -> {
            // Highlighted in place: the rim and a lighter glass, no size change, so the 14dp gutter
            // between the cards (and down to the dock) is the same gap, focused or not.
            halo.setStroke(dp(2), has ? 0xB3FFFFFF : 0x00FFFFFF);
            glass.setColor(has ? 0x7A101014 : glassColor);
            // Focus is the pause: OK must open exactly what is on screen.
            handler.removeCallbacks(deck.tick);
            if (!has) deck.start();   // back on its own slot, not on whichever moment focus left
        });
        return card;
    }

    /** Artwork across the whole card, the words over its darkened left: Apple TV's Up Next. */
    private NowPane nowPane() {
        NowPane p = new NowPane();
        FrameLayout root = new FrameLayout(this);
        p.root = root;

        FrameLayout artFrame = new FrameLayout(this);
        artFrame.setClipToOutline(true);
        artFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(HomeTheme.NOW_RADIUS));
            }
        });
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artFrame.addView(art, new FrameLayout.LayoutParams(-1, -1));
        // Dark under the words, clear by two thirds across, so the still keeps its own light.
        View scrim = new View(this);
        scrim.setBackground(HomeTheme.horizontal(new int[]{0xE6000000, 0x8C000000, 0x00000000}));
        scrim.setVisibility(View.GONE);
        artFrame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        root.addView(artFrame, new FrameLayout.LayoutParams(-1, -1));
        p.art = art;
        p.scrim = scrim;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);

        // The eyebrow says where it is playing: a red dot for "live now" on TiviMate, an amber one
        // for the marquee line when it is a new title on Plex.
        LinearLayout eyebrow = new LinearLayout(this);
        eyebrow.setOrientation(LinearLayout.HORIZONTAL);
        eyebrow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(0xFFFF453A);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(9);
        eyebrow.addView(dot, dlp);
        TextView source = text(16, "sans-serif-medium", 0xCCFFFFFF);
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        source.setSingleLine(true);
        source.setEllipsize(android.text.TextUtils.TruncateAt.END);
        eyebrow.addView(source, new LinearLayout.LayoutParams(-2, -2));
        col.addView(eyebrow, new LinearLayout.LayoutParams(-1, -2));

        TextView title = text(20, "sans-serif-semibold", Color.WHITE);
        title.setLetterSpacing(HomeTheme.TITLE_TRACKING);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title, llp(-1, -2, 0, dp(3), 0, 0));
        TextView meta = text(Typo.CAPTION, "sans-serif", HomeTheme.TEXT_70);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);   // the card's one step below the floor
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.BOTTOM);
        ImageView badge = new ImageView(this);
        // Sized from the mark's own aspect in render(), so FIT_CENTER never letterboxes a wide logo
        // into a square box or a portrait one into a sliver.
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setVisibility(View.GONE);
        // Sits on the caption's baseline, like a glyph: 4dp is the 16sp line's own descent.
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(24), dp(22));
        blp.rightMargin = dp(7);
        blp.bottomMargin = dp(4);
        metaRow.addView(badge, blp);
        metaRow.addView(meta, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(metaRow, llp(-1, -2, 0, dp(4), 0, 0));
        root.addView(col, lp(-1, -2, Gravity.CENTER_VERTICAL,
                dp(HomeTheme.NOW_PAD), 0, dp(HomeTheme.NOW_MARK_W), 0));
        p.words = col;
        p.dot = dot;
        p.badge = badge;
        p.source = source;
        p.title = title;
        p.meta = meta;
        return p;
    }

    /** Reads both halves of the feed once: what is live now, and what is new on Plex. */
    private void fetchNow() {
        final String url = getString(R.string.new_json_url);
        if (url.isEmpty()) return;
        new Thread(() -> {
            List<Feed.Item> all = Feed.parse(Net.text(url, 4000, 4000, Net.MAX_JSON_BYTES));
            List<Feed.Item> live = new ArrayList<>(), fresh = new ArrayList<>();
            for (Feed.Item it : all) {
                if (it.live()) {
                    if (live.size() < LIVE_MAX) live.add(it);
                } else if (fresh.size() < PLEX_MAX) {   // the feed writes Plex newest first
                    fresh.add(it);
                }
            }
            final List<Feed.Item> liveList = live, freshList = fresh;
            if (liveList.isEmpty() && freshList.isEmpty()) {
                runOnUiThread(this::hideNow);
                return;
            }
            final List<Bitmap> liveArt = art(liveList), liveMarks = marks(liveList);
            final List<Bitmap> liveBadges = badges(liveList);
            final List<Bitmap> freshArt = art(freshList), freshMarks = marks(freshList);
            runOnUiThread(() -> {
                showDeck(liveDeck, liveList, liveArt, liveMarks, liveBadges);
                showDeck(plexDeck, freshList, freshArt, freshMarks, new ArrayList<Bitmap>());
                wireFocus();
            });
        }).start();
    }

    private List<Bitmap> art(List<Feed.Item> items) {
        List<Bitmap> out = new ArrayList<>();
        for (Feed.Item it : items)
            out.add(it.image.isEmpty() ? null
                    : Net.image(it.image, 4000, 4000, 1, NOW_PIXELS));
        return out;
    }

    private List<Bitmap> marks(List<Feed.Item> items) {
        List<Bitmap> out = new ArrayList<>();
        for (Feed.Item it : items)
            out.add(it.logo.isEmpty() ? null
                    : Net.image(it.logo, 4000, 4000, 1, NOW_PIXELS));
        return out;
    }

    /** Channel marks for the caption line: EPG icons are a couple of hundred pixels wide. */
    private List<Bitmap> badges(List<Feed.Item> items) {
        List<Bitmap> out = new ArrayList<>();
        for (Feed.Item it : items) {
            Bitmap b = it.badge.isEmpty() ? null
                    : Net.image(it.badge, 4000, 4000, 1, 60_000);
            out.add(b == null ? null : trimMark(b));
        }
        return out;
    }

    /**
     * Strip the transparent surround from a channel mark. EPG icons are padded very differently -
     * one of this guide's puts its whole logo in 33 of 150 rows - and the caption shows the mark,
     * not its margins. Returns the original when there is nothing worth cutting.
     */
    private static Bitmap trimMark(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 4 || h < 4) return b;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, right = -1, top = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if ((px[row + x] >>> 24) < 24) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right <= left || bottom <= top) return b;
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }

    /** A light plate behind a channel mark: EPG logos are drawn for white pages, not for glass. */
    private GradientDrawable markPlate() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setColor(0xF2FFFFFF);
        return g;
    }

    /**
     * True when a mark's own pixels are too dark to read on the card's glass. Only opaque pixels
     * are sampled, every third one in each direction: a transparent surround must not brighten a
     * dark logo, and a 250x150 icon costs about four thousand reads.
     */
    private static boolean darkMark(Bitmap b) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < b.getHeight(); y += 3) {
            for (int x = 0; x < b.getWidth(); x += 3) {
                int c = b.getPixel(x, y);
                if ((c >>> 24) < 40) continue;
                sum += (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000;
                n++;
            }
        }
        return n > 0 && sum / n < 110;   // under ~43% brightness
    }

    /** One deck at a time; an unchanged list keeps the frame that is already showing. */
    private void showDeck(Deck deck, List<Feed.Item> items, List<Bitmap> imgs, List<Bitmap> marks,
                          List<Bitmap> badges) {
        if (deck == null) return;
        if (items.isEmpty()) {
            deck.sig = "";
            deck.items.clear();
            deck.images.clear();
            deck.logos.clear();
            deck.badges.clear();
            handler.removeCallbacks(deck.tick);
            deck.root.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Feed.Item it : items) {
            sb.append(it.title).append('|').append(it.image).append('|')
                    .append(it.logo).append('|').append(it.badge).append('|')
                    .append(it.until).append('|');
        }
        String next = sb.toString();
        deck.root.setVisibility(View.VISIBLE);
        if (next.equals(deck.sig)) {   // same list on screen: re-rendering would restart the crossfade
            deck.start();
            return;
        }
        deck.sig = next;
        deck.items.clear();
        deck.items.addAll(items);
        deck.images.clear();
        deck.images.addAll(imgs);
        deck.logos.clear();
        deck.logos.addAll(marks);
        deck.badges.clear();
        deck.badges.addAll(badges);
        deck.front = 0;
        deck.cur = 0;
        deck.render(0);
        deck.start();
    }

    private void stopNowRotation() {
        for (Deck dk : decks()) if (dk != null) handler.removeCallbacks(dk.tick);
    }

    /** Reads the resume feed; the row stays hidden until Down from the pill asks for it. */
    private void fetchKeep() {
        final String keepUrl = getString(R.string.keep_json_url);
        if (keepUrl.isEmpty()) return;
        new Thread(() -> {
            List<Feed.Item> items = Feed.continueWatching(
                    Feed.parse(Net.text(keepUrl, 4000, 4000, Net.MAX_JSON_BYTES)));
            runOnUiThread(() -> showContinue(items));
        }).start();
    }

    /**
     * The line under the title: the caption the feed already writes ("Sky Sport 2 · until 21:30",
     * "S6 · E4 · New episode"). Only when a live item has no caption is it built from channel and end.
     */
    private String nowMeta(Feed.Item it) {
        if (!it.sub.isEmpty()) return it.sub;
        if (!it.live()) return "";
        StringBuilder sb = new StringBuilder();
        if (!it.channel.isEmpty()) sb.append(it.channel);
        if (it.until > 0) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append("ends ").append(Times.clockTime(it.until,
                    android.text.format.DateFormat.is24HourFormat(this),
                    java.time.ZoneId.systemDefault()));
        }
        return sb.toString();
    }

    private boolean expired(Feed.Item it) {
        return it.until > 0 && it.until <= System.currentTimeMillis() / 1000L;
    }

    private void startNowRotation() {
        for (Deck d : decks()) if (d != null) d.start();
    }

    /** Fade both cards together: they are a row, not two independent things. */
    private void fadeCards(float alpha, long ms) {
        for (Deck d : decks()) if (d != null) d.root.animate().alpha(alpha).setDuration(ms).withLayer().start();
    }

    private void hideNow() {
        for (Deck d : decks()) {
            if (d == null) continue;
            d.sig = "";
            d.items.clear();
            d.images.clear();
            d.logos.clear();
            d.badges.clear();
            handler.removeCallbacks(d.tick);
            d.root.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- dock pill

    /** One glass pill of real app icons; the cursor starts on the first, as it always has. */
    private View buildDockPill() {
        dockPillRow = new LinearLayout(this);
        dockPillRow.setOrientation(LinearLayout.HORIZONTAL);
        dockPillRow.setGravity(Gravity.CENTER_VERTICAL);
        dockPillRow.setClipChildren(false);
        dockPillRow.setClipToPadding(false);
        // The slots already carry 8dp of glow room above and below, so the pill's own vertical pad
        // is 8 less: the glass reads as 14dp of air while the pill stays 92dp tall.
        dockPillRow.setPadding(dp(HomeTheme.DOCK_PAD), dp(HomeTheme.DOCK_PAD_TOP),
                dp(HomeTheme.DOCK_PAD), dp(HomeTheme.DOCK_PAD_BOTTOM));
        GradientDrawable pillGlass = HomeTheme.glassPill(HomeTheme.DOCK_PILL_RADIUS,
                getResources().getDisplayMetrics().density);
        glasses.add(pillGlass);
        dockPillRow.setBackground(pillGlass);
        return dockPillRow;
    }

    /**
     * A dock slot: a 16:9 banner tile with the app's name under it, inside the glass pill. The
     * holder, not the tile, is the focus target, so the 1.08 lift never clips the glow.
     */
    private View dockTile(App app) {
        FrameLayout holder = new FrameLayout(this);
        holder.setId(View.generateViewId());
        holder.setFocusable(true);
        holder.setClickable(true);
        holder.setLongClickable(true);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        // 3dp above, 9 below: the card sits lower in its slot, clear of the glass's top curve.
        holder.setPadding(dp(6), dp(3), dp(6), dp(9));
        holder.setTag(app);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(false);
        final float density = getResources().getDisplayMetrics().density;
        // The tile carries the elevation: its rounded outline is what casts the focus shadow.
        final ImageView shadow = AppTile.shadow(this, density, HomeTheme.DOCK_W, HomeTheme.DOCK_H,
                HomeTheme.DOCK_ICON_RADIUS);
        frame.addView(shadow, AppTile.shadowParams(density));
        final FrameLayout tile = AppTile.dock(this, app, density);
        final View sheen = AppTile.sheen(this);
        tile.addView(sheen, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(tile, new FrameLayout.LayoutParams(-1, -1));
        final View rim = AppTile.focusRim(this, density);
        rim.setAlpha(0f);
        frame.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        col.addView(frame, new LinearLayout.LayoutParams(dp(HomeTheme.DOCK_W), dp(HomeTheme.DOCK_H)));

        TextView name = text(Typo.CAPTION, "sans-serif-medium", 0xCCFFFFFF);
        // setTextSize, not text(): the caption floor is 18sp and a dock label must stay a label.
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, HomeTheme.DOCK_NAME);
        name.setText(app.label);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        name.setTextColor(Color.WHITE);
        // tvOS names only the app under the cursor; the line keeps its height so nothing moves.
        name.setAlpha(0f);
        col.addView(name, llp(dp(HomeTheme.DOCK_W + 12), -2, 0, dp(HomeTheme.DOCK_NAME_GAP), 0, 0));

        holder.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        holder.setOnFocusChangeListener((v, has) -> {
            lift(v, has, HomeTheme.DOCK_FOCUS_SCALE);
            shadow.animate().alpha(has ? 1f : 0f).setDuration(HomeTheme.MS_FOCUS).start();
            rim.animate().alpha(has ? 1f : 0f).setDuration(HomeTheme.MS_FOCUS).start();
            name.animate().alpha(has ? 1f : 0f).setDuration(HomeTheme.MS_FOCUS).start();
            if (has) AppTile.sweep(sheen, tile.getWidth() > 0 ? tile.getWidth() : dp(HomeTheme.DOCK_W));
            else { sheen.animate().cancel(); sheen.setAlpha(0f); }
            if (!has) return;
            lastFocusedId = v.getId();
            glideTo(0);
        });
        holder.setOnClickListener(v -> {
            pressDip(v, HomeTheme.DOCK_FOCUS_SCALE);
            getSharedPreferences("launches", MODE_PRIVATE).edit()
                    .putLong(app.pkg, System.currentTimeMillis()).apply();
            boolean opened = launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (!opened) pressUndo(v, HomeTheme.DOCK_FOCUS_SCALE);
        });
        holder.setOnLongClickListener(v -> { showAppMenu(v, app); return true; });
        return holder;
    }

    /** "All apps" is the grid that has always lived below the fold; this just takes you there. */
    private void showAllApps() {
        if (gridCards.isEmpty()) return;
        gridCards.get(0).requestFocus();
        scrollToRow(gridCards.get(0));
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

    // ---------------------------------------------------------------- continue watching

    private LinearLayout cwReveal, cwRow;
    private android.widget.HorizontalScrollView cwScroll;
    private final List<View> cwCards = new ArrayList<>();
    private boolean cwOpen;

    /**
     * The row above the pill: what is part-watched, side-scrolling, hidden at rest. Down from the
     * dock slides it up over a deeper floor; Up or Back puts it away and nothing else moves.
     */
    private LinearLayout buildContinue() {
        cwReveal = new LinearLayout(this);
        cwReveal.setOrientation(LinearLayout.VERTICAL);
        cwReveal.setClipChildren(false);
        cwReveal.setVisibility(View.GONE);

        TextView head = text(Typo.SECTION, "sans-serif-semibold", 0xF2FFFFFF);
        head.setText("Continue Watching");
        head.setShadowLayer(dp(10), 0, dp(1), 0x99000000);
        cwReveal.addView(head, llp(-2, -2, dp(HomeTheme.SAFE), 0, 0, dp(HomeTheme.CW_HEAD_GAP)));

        cwScroll = new android.widget.HorizontalScrollView(this);
        cwScroll.setHorizontalScrollBarEnabled(false);
        cwScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        cwScroll.setClipToPadding(false);
        cwScroll.setPadding(dp(HomeTheme.SAFE), 0, dp(HomeTheme.SAFE), 0);
        cwScroll.setFocusable(false);
        cwScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        cwRow = new LinearLayout(this);
        cwRow.setOrientation(LinearLayout.HORIZONTAL);
        cwRow.setClipChildren(false);   // or the focused tile's 1.06 scale is cut back to its slot
        cwScroll.addView(cwRow, new FrameLayout.LayoutParams(-2, -2));
        cwReveal.addView(cwScroll, llp(-1, dp(HomeTheme.CW_H), 0, 0, 0, 0));
        return cwReveal;
    }

    private void revealContinue() {
        if (cwOpen || cwCards.isEmpty()) return;
        cwOpen = true;
        cwReveal.setVisibility(View.VISIBLE);
        cwReveal.setTranslationY(dp(36));
        cwReveal.setAlpha(0f);
        cwReveal.animate().translationY(0f).alpha(1f)
                .setDuration(HomeTheme.MS_REVEAL)
                .setInterpolator(HomeTheme.ease()).withLayer().start();
        cwWash.setVisibility(View.VISIBLE);
        cwWash.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).start();
        // The row is a layer, not a neighbour: the clock and the card fade back so nothing shows
        // through the tiles.
        if (statusBlock != null) statusBlock.animate().alpha(0.12f).setDuration(HomeTheme.MS_REVEAL).start();
        if (topActions != null) topActions.animate().alpha(0.12f).setDuration(HomeTheme.MS_REVEAL).start();
        fadeCards(0.12f, HomeTheme.MS_REVEAL);
        cwCards.get(0).requestFocus();
    }

    private void hideContinue() {
        if (!cwOpen) return;
        cwOpen = false;
        cwReveal.animate().translationY(dp(36)).alpha(0f).setDuration(HomeTheme.MS_REVEAL).withLayer()
                .withEndAction(() -> { if (!cwOpen) cwReveal.setVisibility(View.GONE); }).start();
        cwWash.animate().alpha(0f).setDuration(HomeTheme.MS_REVEAL)
                .withEndAction(() -> { if (!cwOpen) cwWash.setVisibility(View.GONE); }).start();
        if (statusBlock != null) statusBlock.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).start();
        if (topActions != null) topActions.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).start();
        fadeCards(1f, HomeTheme.MS_REVEAL);
    }

    /** Rebuilds only when the list really changed: every rebuild downloads its artwork again. */
    private void showContinue(List<Feed.Item> items) {
        if (items.isEmpty()) {
            hideContinue();
            cwRow.removeAllViews();
            cwCards.clear();
            cwSig = "";
            cwReveal.setVisibility(View.GONE);
            rememberCwCount(0);
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
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    dp(HomeTheme.CW_W), dp(HomeTheme.CW_H));
            p.setMargins(i == 0 ? 0 : dp(HomeTheme.CW_GAP), 0, 0, 0);
            cwRow.addView(tile, p);
            cwCards.add(tile);
        }
        rememberCwCount(items.size());
        wireFocus();
    }

    /** The cover draws skeletons only when the last known row was not empty. */
    private void rememberCwCount(int n) {
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putInt("cw_count", n).apply();
    }

    /** Plex's own installed icon; no bundled brand file. Null when Plex is not installed. */
    private static Drawable plexIcon;

    private Drawable plexBadge() {
        if (plexIcon == null) {
            try {
                plexIcon = getPackageManager().getApplicationIcon("com.plexapp.android");
            } catch (Exception e) {
                return null;
            }
        }
        return plexIcon;
    }

    /** A 16:9 resume tile: backdrop, title, the one metadata line, and how far through. */
    private View cwTile(Feed.Item it) {
        // The rim belongs to a holder two dp larger than the artwork, so focus can never paint over
        // the progress bar at the bottom of the tile.
        FrameLayout holder = new FrameLayout(this);
        holder.setId(View.generateViewId());
        holder.setFocusable(true);
        holder.setClickable(true);
        holder.setTag(it);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        holder.setPadding(dp(2), dp(2), dp(2), dp(2));
        final GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(dp(HomeTheme.CW_RADIUS + 2));
        rim.setStroke(dp(1), HomeTheme.RIM);
        holder.setBackground(rim);

        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(HomeTheme.CW_RADIUS));
            }
        });
        card.setBackgroundColor(0xFF14161C);
        final float density = getResources().getDisplayMetrics().density;
        final ImageView shadow = AppTile.shadow(this, density, HomeTheme.CW_W, HomeTheme.CW_H,
                HomeTheme.CW_RADIUS);
        holder.addView(shadow, AppTile.shadowParams(density));
        holder.addView(card, new FrameLayout.LayoutParams(-1, -1));

        int art = Cards.art(it.image, it.logo);
        if (art == Cards.IMAGE) {
            ImageView still = new ImageView(this);
            still.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(still, new FrameLayout.LayoutParams(-1, -1));
            loadArt(still, it.image);
        } else if (art == Cards.LOGO) {   // no still: the show's own mark, contained
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
                    new int[]{HomeTheme.darken(0xFFE5A00D, 0.4f), 0xFF14161C}));
        }

        // Who it resumes in: Plex's own installed icon, top-left, with a shadow so it reads on any still.
        Drawable plex = plexBadge();
        if (plex != null) {
            FrameLayout badge = new FrameLayout(this);
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(dp(8));
            bb.setColor(0x99000000);
            badge.setBackground(bb);
            badge.setElevation(dp(3));
            ImageView mark = new ImageView(this);
            mark.setImageDrawable(plex);
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            badge.addView(mark, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
            // Top-right: the title and metadata own the bottom-left of the tile.
            card.addView(badge, lp(dp(22), dp(22), Gravity.TOP | Gravity.END,
                    0, dp(10), dp(10), 0));
        }

        // The lower 55% only: enough for two lines of text over any still, no black band up top.
        View shade = new View(this);
        shade.setBackground(HomeTheme.vertical(
                new int[]{0x00000000, 0xB3000000, 0xD9000000}));
        card.addView(shade, lp(-1, (int) (dp(HomeTheme.CW_H) * HomeTheme.CW_SHADE),
                Gravity.BOTTOM, 0, 0, 0, 0));

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(Typo.BODY, "sans-serif-semibold", Color.WHITE);
        title.setText(it.title);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(6), 0, dp(1), 0x99000000);   // a white still can still fight the gradient
        lines.addView(title);
        TextView meta = text(Typo.CAPTION, "sans-serif", 0xC7FFFFFF);
        meta.setText(it.sub);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lines.addView(meta);
        // Clear of the 4dp bar and its 12dp inset: the metadata is never printed over the progress.
        int below = HomeTheme.PROGRESS_INSET + HomeTheme.PROGRESS_H + 4;
        card.addView(lines, lp(-1, -2, Gravity.BOTTOM, dp(12), 0, dp(12), dp(below)));

        if (it.progress > 0) {
            int track = dp(HomeTheme.CW_W) - dp(HomeTheme.PROGRESS_INSET) * 2;
            View groove = new View(this);
            GradientDrawable gg = new GradientDrawable();
            gg.setCornerRadius(dp(2));
            gg.setColor(0x40FFFFFF);
            groove.setBackground(gg);
            card.addView(groove, lp(track, dp(HomeTheme.PROGRESS_H), Gravity.BOTTOM,
                    dp(HomeTheme.PROGRESS_INSET), 0, dp(HomeTheme.PROGRESS_INSET), dp(HomeTheme.PROGRESS_INSET)));
            View bar = new View(this);
            GradientDrawable fb = new GradientDrawable();
            fb.setCornerRadius(dp(2));
            fb.setColor(0xFFFFFFFF);
            bar.setBackground(fb);
            card.addView(bar, lp(Math.max(dp(4), (int) (track * Math.min(1.0, it.progress))),
                    dp(HomeTheme.PROGRESS_H), Gravity.BOTTOM | Gravity.START,
                    dp(HomeTheme.PROGRESS_INSET), 0, 0, dp(HomeTheme.PROGRESS_INSET)));
        }

        // The dock's focus language: a light 1dp edge, a dropped shadow and one sheen across.
        final View sheen = AppTile.sheen(this);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));
        holder.setOnFocusChangeListener((v, has) -> {
            rim.setStroke(dp(1), has ? HomeTheme.FOCUS_RIM : HomeTheme.RIM);
            // setScale, not animate(): inside the scroll view the property animator never produced a
            // visible scale change (the rim moved, the tile did not). Setting it is what renders.
            float s = has ? HomeTheme.CW_FOCUS_SCALE : 1f;
            v.setScaleX(s);
            v.setScaleY(s);
            shadow.animate().alpha(has ? 1f : 0f).setDuration(HomeTheme.MS_FOCUS).start();
            if (has) AppTile.sweep(sheen, card.getWidth() > 0 ? card.getWidth() : dp(HomeTheme.CW_W));
            else { sheen.animate().cancel(); sheen.setAlpha(0f); }
            if (has) {
                lastFocusedId = v.getId();
                cwScroll.smoothScrollTo(Math.max(0, v.getLeft() - dp(HomeTheme.SAFE)), 0);
            }
        });
        holder.setOnClickListener(v -> openItem(it));
        return holder;
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

    // ---------------------------------------------------------------- settings

    private final List<View> settingsRows = new ArrayList<>();
    private TextView batterySignValue;

    private FrameLayout buildSettingsPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xB3000000);
        page.setVisibility(View.GONE);
        page.setClickable(true);
        page.setOnClickListener(v -> hideSettings());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(28), dp(24), dp(28), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0xF20E0E10);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        sheet.setBackground(bg);
        sheet.setClickable(true);   // a click on the sheet is not a click outside it
        sheet.setOnClickListener(v -> { });

        TextView title = text(28, "sans-serif-light", Color.WHITE);
        title.setText("Settings");
        sheet.addView(title);
        TextView hint = text(Typo.CAPTION, "sans-serif", HomeTheme.TEXT_60);
        hint.setText("Back returns home");
        sheet.addView(hint, llp(-2, -2, 0, dp(2), 0, dp(18)));

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        settingsRows.clear();

        rows.addView(settingsRow("Network", netLabel, v -> openWifiSettings()));
        if (hasPassthroughInputs()) {
            rows.addView(settingsRow("Inputs", "Switch HDMI source", v -> {
                hideSettings();
                showInputs(v);
            }));
        }
        rows.addView(batteryRow());
        rows.addView(settingsRow("Amazon settings", "Display, apps, device",
                v -> openFullSettings()));
        sheet.addView(rows, new LinearLayout.LayoutParams(-1, -2));

        page.addView(sheet, lp(560, -2, Gravity.CENTER, 0, 0, 0, 0));
        return page;
    }

    private View settingsRow(String label, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setId(View.generateViewId());
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.TRANSPARENT);
        row.setBackground(bg);
        TextView t = text(20, "sans-serif-medium", Color.WHITE);
        t.setText(label);
        row.addView(t);
        TextView s = text(Typo.CAPTION, "sans-serif", HomeTheme.TEXT_60);
        s.setText(sub);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(s, llp(-1, -2, 0, dp(2), 0, 0));
        row.setOnFocusChangeListener((v, has) -> bg.setColor(has ? 0x1FFFFFFF : Color.TRANSPARENT));
        row.setOnClickListener(click);
        settingsRows.add(row);
        return row;
    }

    /** The one preference this screen owns: which sign of the battery power means charging. */
    private View batteryRow() {
        boolean positive = getSharedPreferences("launcher", MODE_PRIVATE)
                .getBoolean("bat_positive_charging", Batteries.DEFAULT_POSITIVE_IS_CHARGING);
        LinearLayout row = (LinearLayout) settingsRow("Battery power sign",
                positive ? "Positive = charging" : "Positive = discharging", v -> { });
        batterySignValue = (TextView) row.getChildAt(1);
        row.setOnClickListener(v -> {
            boolean now = getSharedPreferences("launcher", MODE_PRIVATE)
                    .getBoolean("bat_positive_charging", Batteries.DEFAULT_POSITIVE_IS_CHARGING);
            getSharedPreferences("launcher", MODE_PRIVATE).edit()
                    .putBoolean("bat_positive_charging", !now).apply();
            batterySignValue.setText(!now ? "Positive = charging" : "Positive = discharging");
            // Re-read the snapshot so the line changes under the cursor, not on the next poll.
            if (lastHa != null) applyStatus(lastHa, lastHa.optJSONObject("ages"));
        });
        return row;
    }

    private void showSettings() {
        if (settingsPage == null) return;
        hideContinue();
        settingsPage.setVisibility(View.VISIBLE);
        settingsPage.setAlpha(0f);
        settingsPage.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).withLayer().start();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (!settingsRows.isEmpty()) settingsRows.get(0).requestFocus();
    }

    private void hideSettings() {
        if (settingsPage == null) return;
        settingsPage.setVisibility(View.GONE);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (statusBlock != null) statusBlock.requestFocus();
    }

    // ---------------------------------------------------------------- Home Assistant panel

    private static final int HA_PANEL_W_DP = HomeTheme.HA_W;   // 35% of a 960dp screen
    private boolean haOpen;
    private View haScrim;

    /** The detail panel: a glass sheet that slides in from the right, over the home screen. */
    private View buildHaPanel() {
        haPanel = new LinearLayout(this);
        haPanel.setOrientation(LinearLayout.VERTICAL);
        // The vertical padding is deliberately uneven: the hero's cap leaves more air above it than
        // its digits occupy, so an even 16/16 reads as a block sitting high in the glass. 25/7 moves
        // the text and tiles down 9dp and keeps the sum at 32, so the sheet itself never moves.
        haPanel.setPadding(dp(24), dp(25), dp(24), dp(7));
        haFrost = new Frost(haPanel, dp(24), Math.max(1, dp(1)));
        haPanel.setBackground(haFrost);
        // No elevation: on API 28 the shadow of a rounded sheet is drawn as a plain rectangle, which
        // showed up as a hard dark strip down the panel's left edge (and never matched the corners).
        haPanel.setFocusable(true);
        haPanel.setClickable(true);
        haPanel.setId(View.generateViewId());
        haPanel.setVisibility(View.GONE);
        haPanel.setOnClickListener(v -> closeHa());

        haBig = text(Typo.HERO, "sans-serif-light", Color.WHITE);   // the screen's ceiling
        Typo.numbers(haBig);
        haPanel.addView(haBig);

        // Condition and "feels" share a line: the sheet has a fixed 492dp of screen on a 1080p TV,
        // and a panel taller than that gets its last rows measured to nothing.
        haSub = text(Typo.BODY, "sans-serif-medium", 0xE6FFFFFF);
        haSub.setSingleLine(true);
        haSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        haPanel.addView(haSub);

        View rule = new View(this);
        rule.setBackgroundColor(0x1FFFFFFF);
        haPanel.addView(rule, llp(-1, dp(1), 0, dp(12), 0, dp(10)));

        // Bins carries "General · Tomorrow", so it gets the room; AC only ever says "Off".
        final float[][] weights = {{1f, 1f, 1f}, {0.85f, 0.6f, 1.55f}};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                View tile = haTile(r * 3 + c);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, weights[r][c]);
                p.setMargins(c == 0 ? 0 : dp(8), 0, 0, 0);
                row.addView(tile, p);
            }
            haTileRows[r] = row;
            haPanel.addView(row, llp(-1, -2, 0, 0, 0, r == 0 ? dp(10) : 0));
        }

        // The weather outside, in the sheet's own line style: wind and humidity, then the sun.
        haWeather = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haWeatherRow = haLine(R.drawable.ic_wind, haWeather, 1);
        haPanel.addView(haWeatherRow, llp(-1, -2, 0, dp(14), 0, 0));
        haSun = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haSunRow = haLine(R.drawable.ic_sun, haSun, 1);
        haPanel.addView(haSunRow, llp(-1, -2, 0, dp(5), 0, 0));
        haSolar = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haSolarRow = haLine(R.drawable.ic_solar, haSolar, 1);
        haSolarIcon = (ImageView) ((android.view.ViewGroup) haSolarRow).getChildAt(0);
        haPanel.addView(haSolarRow, llp(-1, -2, 0, dp(5), 0, 0));
        haRain = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haRainRow = haLine(R.drawable.ic_rain, haRain, 2);
        haPanel.addView(haRainRow, llp(-1, -2, 0, dp(5), 0, 0));
        haNext = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haNextRow = haLine(R.drawable.ic_calendar, haNext, 1);   // one line: the ellipsis, not a wrap
        haPanel.addView(haNextRow, llp(-1, -2, 0, dp(5), 0, 0));
        haPeople = text(Typo.CAPTION, "sans-serif", 0xF2FFFFFF);
        haPeopleRow = haLine(R.drawable.ic_person, haPeople, 1);
        haPanel.addView(haPeopleRow, llp(-1, -2, 0, dp(5), 0, 0));
        // Who is home is the last line. Plex's "what is playing elsewhere" used to sit under it and
        // pushed the sheet past the 492dp the panel has between its margins, which measured the
        // last rows to nothing; one line of television elsewhere is not worth a clipped sheet.
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
        TextView value = text(Typo.BODY, "sans-serif-semibold", Color.WHITE);
        value.setGravity(Gravity.CENTER);
        value.setMaxLines(2);   // "General \u00b7 Tomorrow" wraps instead of being cut
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setIncludeFontPadding(false);   // Inter's own metrics: six cells have no room to spare
        Typo.numbers(value);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.setMargins(0, dp(4), 0, 0);
        box.addView(value, vp);
        TextView label = text(Typo.CAPTION, "sans-serif", HA_LABEL);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        label.setText(HA_LABELS[i]);
        box.addView(label, new LinearLayout.LayoutParams(-1, -2));
        haTileBox[i] = box;
        haTileValue[i] = value;
        haTileLabel[i] = label;
        haTileIcon[i] = icon;
        return box;
    }

    /** OK on the status block opens the sheet; it takes focus so the D-pad cannot wander behind it. */
    private void openHa() {
        if (haPanel == null || haOpen) return;
        haOpen = true;
        stopNowRotation();
        hideContinue();
        haScrim.setVisibility(View.VISIBLE);
        haScrim.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).start();
        // The clock and the card would otherwise be legible through the sheet's top edge.
        if (statusBlock != null) statusBlock.animate().alpha(0f).setDuration(HomeTheme.MS_REVEAL).start();
        if (topActions != null) topActions.animate().alpha(0f).setDuration(HomeTheme.MS_REVEAL).start();
        fadeCards(0f, HomeTheme.MS_REVEAL);
        if (dockPillRow != null) dockPillRow.animate().alpha(0.15f).setDuration(HomeTheme.MS_REVEAL).start();
        haPanel.setVisibility(View.VISIBLE);
        haPanel.requestLayout();   // lines that came or went while it was closed need a fresh measure
        haPanel.setTranslationX(dp(HA_PANEL_W_DP));
        haPanel.setAlpha(0f);
        haPanel.animate().translationX(0).alpha(1f)
                .setInterpolator(HomeTheme.ease()).setDuration(HomeTheme.MS_REVEAL).withLayer().start();
        haPanel.requestFocus();
    }

    private void closeHa() {
        if (!haOpen) return;
        haOpen = false;
        haPanel.animate().translationX(dp(HA_PANEL_W_DP)).alpha(0f).setDuration(HomeTheme.MS_REVEAL).withLayer()
                .withEndAction(() -> {
                    if (!haOpen) haPanel.setVisibility(View.GONE);
                }).start();
        haScrim.animate().alpha(0f).setDuration(HomeTheme.MS_REVEAL).withEndAction(() -> {
            if (!haOpen) haScrim.setVisibility(View.GONE);
        }).start();
        if (statusBlock != null) {
            statusBlock.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).start();
            statusBlock.requestFocus();
        }
        if (topActions != null) topActions.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).start();
        fadeCards(1f, HomeTheme.MS_REVEAL);
        if (dockPillRow != null) dockPillRow.animate().alpha(1f).setDuration(HomeTheme.MS_REVEAL).start();
        startNowRotation();
    }

    private View haLine(int iconRes, TextView t, int lines) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(HA_LABEL));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        t.setMaxLines(lines);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Seven possible lines inside 492dp of TV: the font's own leading is enough, and the
        // extra half-line Android adds around it is what pushed the last row off the sheet.
        t.setIncludeFontPadding(false);
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
        // Gone, not invisible: a row with a missing tile spreads the rest across it instead of
        // leaving a hole at its end (Inside and AC with no bins left the right third empty).
        haTileBox[i].setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) return;
        haTileValue[i].setText(value);
        long age = ages == null ? 0 : ages.optLong(HA_KEYS[i], 0);
        boolean stale = Times.stale(age);
        haTileBox[i].setAlpha(stale ? HomeTheme.STALE : 1f);
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

    private static long age(JSONObject ages, String key) {
        return ages == null ? 0 : ages.optLong(key, 0);
    }

    private static double number(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * The plain lines under the clock. Each is hidden when the snapshot has nothing for it, and a
     * value that has not updated in 15 minutes is dimmed exactly as the sheet dims its own tile.
     */
    private void applyStatus(JSONObject j, JSONObject ages) {
        String temp = j.optString("temp", "");
        String cond = j.optString("condition", "");
        String inside = j.optString("inside", "");
        StringBuilder weather = new StringBuilder();
        if (!temp.isEmpty()) weather.append(temp).append("\u00B0");
        if (!cond.isEmpty()) {
            if (weather.length() > 0) weather.append(' ');
            weather.append(capitalise(cond));
        }
        if (!inside.isEmpty()) {
            if (weather.length() > 0) weather.append("  \u00B7  ");
            weather.append(Times.kw(inside)).append("\u00B0 inside");
        }
        setStatusLine(statusWeatherRow, statusWeather, weather.toString(), HomeTheme.TEXT_90,
                Batteries.alpha(Times.staleWeather(age(ages, "temp"))));

        String bat = j.optString("battery", "");
        if (bat.isEmpty()) {
            statusBatteryRow.setVisibility(View.GONE);
        } else {
            double soc = number(bat, 0);
            // Optional: signed watts. Until Home Assistant sends it the line shows the charge and
            // an idle state, rather than inventing a direction from the solar figure.
            double watts = number(j.optString("bat_power", "0"), 0);
            boolean posCharge = getSharedPreferences("launcher", MODE_PRIVATE)
                    .getBoolean("bat_positive_charging", Batteries.DEFAULT_POSITIVE_IS_CHARGING);
            int state = Batteries.state(soc, watts, posCharge);
            boolean stale = Times.stale(age(ages, "battery"));
            String name = j.optString("battery_name", "Battery");
            // The words stay white like every other line; the mark carries the state, so a
            // charging battery is green and a draining one orange without tinting the sentence.
            setStatusLine(statusBatteryRow, statusBattery, Batteries.line(name, soc, state),
                    HomeTheme.TEXT_90, Batteries.alpha(stale));
            tintBatteryIcon(stale ? Batteries.GREY : Batteries.color(state));
            // "Battery 66% ↑  ☀ 5.3 kW" while the roof is producing; the sun mark is the same
            // one the sheet's Solar tile uses. At night the whole segment is gone, not a "0 kW".
            String solar = j.optString("solar", "");
            boolean producing = !solar.isEmpty() && number(solar, 0) > 0.05;
            statusSolarGroup.setVisibility(producing ? View.VISIBLE : View.GONE);
            if (producing) {
                statusSolar.setText(Times.kw(solar) + " kW");
                statusSolarGroup.setAlpha(Times.stale(age(ages, "solar")) ? HomeTheme.STALE : 1f);
            }
        }

        String ac = j.optString("ac", "");
        String acText = "";
        if (!ac.isEmpty()) {
            String low = ac.toLowerCase(Locale.US);
            if (low.startsWith("off")) {
                acText = "Air con off";
            } else {
                String verb = low.startsWith("cool") ? "cooling"
                        : low.startsWith("heat") ? "heating"
                        : low.startsWith("dry") ? "drying"
                        : low.startsWith("fan") ? "fan only" : low;
                // "to 22\u00B0" only when the snapshot carries the set point; today it does not.
                String to = j.optString("ac_to", "");
                acText = "Air con " + verb + (to.isEmpty() ? "" : " to " + to + "\u00B0");
            }
        }
        setStatusLine(statusAcRow, statusAc, acText, HomeTheme.TEXT_90, Batteries.alpha(false));

        String bins = Cards.binsGlance(j.optString("bins", ""));
        setStatusLine(statusBinsRow, statusBins, bins, HomeTheme.ACCENT,
                Batteries.alpha(Times.stale(age(ages, "bins"))));
    }

    private void showHa(JSONObject j) {
        long now = System.currentTimeMillis() / 1000L;
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        JSONObject ages = j.optJSONObject("ages");
        lastHa = j;   // so the battery-sign setting can re-render without waiting for a poll

        // The wired pill may have been waiting for this poll before it could say its speed.
        String eth = j.optString("eth_mbps", "");
        if (!eth.equals(haEthMbps)) {
            haEthMbps = eth;
            if (isEthernet()) updateWifi();
        }

        applyStatus(j, ages);

        haBig.setText(j.optString("temp", "--") + "\u00B0");
        String cond = capitalise(j.optString("condition", ""));
        String feels = j.optString("feels", "");
        String condition = cond.isEmpty() ? "" : feels.isEmpty() ? cond : cond + " \u00B7 Feels " + feels + "\u00B0";
        haSub.setText(condition);
        haSub.setVisibility(condition.isEmpty() ? View.GONE : View.VISIBLE);

        String solar = j.optString("solar", "");
        setHaTile(0, solar.isEmpty() ? "" : Times.kw(solar) + " kW", solar, ages, HA_LABELS[0]);
        String battery = j.optString("battery", "");
        // Whole hours, not "29 h 15 m": on the battery's own line the minutes pushed the line past
        // the sheet's width, and the tile's label is the one place that has to stay free for
        // "16m ago" when the inverter goes quiet.
        String runway = Cards.runway(j.optString("battery_left", ""));
        setHaTile(1, battery.isEmpty() ? "" : battery + "%", battery, ages, HA_LABELS[1]);
        String house = j.optString("house", "");
        setHaTile(2, house.isEmpty() ? "" : Times.kw(house) + " kW", house, ages, HA_LABELS[2]);
        String inside = j.optString("inside", "");
        setHaTile(3, inside.isEmpty() ? "" : Times.kw(inside) + "\u00B0", inside, ages, HA_LABELS[3]);
        String ac = j.optString("ac", "");
        setHaTile(4, ac.isEmpty() ? "" : capitalise(ac), ac, ages, HA_LABELS[4]);
        // One word of value, one line of label: "Tomorrow" over "General bins", never a wrapped cell.
        String bins = j.optString("bins", "");
        String due = Cards.binsDay(bins);
        String kind = Cards.binsKind(bins);
        setHaTile(5, due, due.isEmpty() ? "" : bins, ages,
                kind.isEmpty() ? "Bins" : kind + " bins");
        alertTile(5, !due.isEmpty());

        for (int r = 0; r < 2; r++) {   // an all-empty row is a gap, not a row
            boolean any = false;
            for (int c = 0; c < 3; c++) any |= haTileBox[r * 3 + c].getVisibility() == View.VISIBLE;
            haTileRows[r].setVisibility(any ? View.VISIBLE : View.GONE);
        }

        // Outside, from the same weather entity the hero reads: wind, humidity, then the sun.
        String weather = Cards.weatherLine(j.optString("wind", ""), j.optString("wind_unit", ""),
                j.optString("humidity", ""));
        haWeatherRow.setVisibility(weather.isEmpty() ? View.GONE : View.VISIBLE);
        haWeather.setText(weather);
        long riseAt = j.optLong("sunrise_at", 0), setAt = j.optLong("sunset_at", 0);
        String sun = Cards.sunLine(riseAt, setAt, is24, java.time.ZoneId.systemDefault());
        haSunRow.setVisibility(sun.isEmpty() ? View.GONE : View.VISIBLE);
        haSun.setText(sun);

        // The day's solar and how long the battery can carry the house: the two numbers a solar
        // house plans around, in one line that fits.
        String todayKwh = j.optString("energy_today", "");
        String solarToday = Cards.solarLine(todayKwh, runway);
        haSolarRow.setVisibility(solarToday.isEmpty() ? View.GONE : View.VISIBLE);
        haSolarIcon.setImageResource(todayKwh.isEmpty() ? R.drawable.ic_battery : R.drawable.ic_solar);
        haSolar.setText(solarToday);

        String rainRaw = j.optString("rain", "");
        String rain = Cards.rainLine(rainRaw);
        haRainRow.setVisibility(rain.isEmpty() ? View.GONE : View.VISIBLE);
        haRain.setText(rain);
        haRain.setTextColor(Cards.wetRain(rainRaw) ? HA_ALERT : 0xF2FFFFFF);

        // Codes stripped, the typed-in time put in the house format, "Next:" dropped and the
        // distance in words appended - all in one place, so this line matches the clock above it.
        String next = Times.calendarLine(j.optString("next", ""),
                is24, j.optLong("next_at", 0), now);
        haNextRow.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        haNext.setText(next);
        // "in 47 min" is the first thing to go when the line will not fit: the time and the event
        // are the news, and an ellipsis through the event's name says neither.
        final String nextShort = Times.calendarLine(j.optString("next", ""), is24, 0, now);
        haNext.post(() -> {
            android.text.Layout l = haNext.getLayout();
            if (l != null && l.getLineCount() > 0 && l.getEllipsisCount(0) > 0) haNext.setText(nextShort);
        });

        // Who is in, named when the snapshot names them. Silent when there are no person entities.
        String people = Cards.peopleLine(j.optString("people", ""), j.optString("people_names", ""));
        haPeopleRow.setVisibility(people.isEmpty() ? View.GONE : View.VISIBLE);
        haPeople.setText(people);
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
            // TiviMate's own name says nothing about what it is for. On the couch it is where
            // live TV lives; the package is a setting, so a reinstall keeps the plain name.
            if (!liveTvPkg.isEmpty() && a.pkg.equals(liveTvPkg)) a.label = "Live TV";
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.icon = ri.loadIcon(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : a.icon;
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

        dockPillRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();
        dockSnapshot = Collections.unmodifiableList(new ArrayList<>(dock));

        for (int i = 0; i < dock.size(); i++) {
            View slot = dockTile(dock.get(i));
            dockCards.add(slot);   // the pill's slots are the dock's focus targets
            // The slot's 6dp of glow room counts toward the gap, and it has a fixed size: a
            // wrap-content slot holding a match-parent glow measures as large as its parent.
            int gap = Math.max(0, dp(HomeTheme.DOCK_GAP) - dp(12));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    dp(HomeTheme.DOCK_W + 12), dp(HomeTheme.DOCK_SLOT_H));
            p.setMargins(i == 0 ? 0 : gap, 0, i == dock.size() - 1 ? 0 : gap, 0);
            dockPillRow.addView(slot, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i));
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

        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        // A rebuilt view is never the right place to land: the first focusable view gains focus
        // during layout, which used to pin the cursor above the dock on every cold start.
        if (last == null || last.getParent() == null) last = null;
        focusNow(last != null ? last : (dockCards.isEmpty() ? null : dockCards.get(0)));
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. Grid only now:
     *  the dock draws square icons through {@link #dockIcon}. */
    private View tile(App app) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        // Painting lives in AppTile, shared with the Home-key cover: same background, same art
        // padding, same rim, so the cover's dock cannot drift away from this one.
        FrameLayout card = AppTile.card(this, app, getResources().getDisplayMetrics().density);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        // The radius and its clip live in AppTile, so the cover's dock gets them too.

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
        box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setLongClickable(true);
        card.setOnLongClickListener(v -> { showAppMenu(v, app); return true; });
        card.setOnClickListener(v -> {
            pressDip(v, HomeTheme.DOCK_FOCUS_SCALE);
            getSharedPreferences("launches", MODE_PRIVATE).edit().putLong(app.pkg, System.currentTimeMillis()).apply();
            boolean opened = launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (!opened) pressUndo(v, HomeTheme.DOCK_FOCUS_SCALE);
        });
        card.setOnFocusChangeListener((v, has) -> {
            lift(v, has, HomeTheme.DOCK_FOCUS_SCALE);
            v.animate().translationZ(has ? dp(10) : 0).setDuration(HomeTheme.MS_FOCUS).start();
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
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(HomeTheme.MS_FOCUS).start();
            if (!has) return;
            lastFocusedId = v.getId();
            dimBackdrop(true);
            scrollToRow(box);
        });

        gridCards.add(card);
        return box;
    }

    /**
     * Explicit D-pad targets: one row per press, and nothing wraps. Down from the pill opens the
     * hidden resume row instead of moving the cursor (see dispatchKeyEvent), so the dock's own
     * down target only matters once that row is open.
     */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size(), c = cwCards.size();
        boolean hasLive = liveDeck != null && liveDeck.showing();
        boolean hasPlex = plexDeck != null && plexDeck.showing();
        // Down from the top bar lands on the right-hand card, which is what sits under it.
        int cardId = hasPlex ? plexDeck.root.getId()
                : hasLive ? liveDeck.root.getId() : View.NO_ID;
        int firstId = d > 0 ? dockCards.get(0).getId() : View.NO_ID;

        if (statusBlock != null) {
            statusBlock.setNextFocusUpId(statusBlock.getId());
            statusBlock.setNextFocusLeftId(statusBlock.getId());
            statusBlock.setNextFocusRightId(netChip == null ? statusBlock.getId() : netChip.getId());
            statusBlock.setNextFocusDownId(cardId == View.NO_ID ? firstId : cardId);
        }
        if (netChip != null) {
            netChip.setNextFocusLeftId(statusBlock == null ? netChip.getId() : statusBlock.getId());
            netChip.setNextFocusRightId(actionApps == null ? netChip.getId() : actionApps.getId());
            netChip.setNextFocusUpId(netChip.getId());
            netChip.setNextFocusDownId(cardId == View.NO_ID ? firstId : cardId);
        }
        if (actionApps != null) {
            actionApps.setNextFocusLeftId(netChip == null
                    ? (statusBlock == null ? actionApps.getId() : statusBlock.getId()) : netChip.getId());
            actionApps.setNextFocusRightId(actionSettings == null ? actionApps.getId() : actionSettings.getId());
            actionApps.setNextFocusUpId(actionApps.getId());
            actionApps.setNextFocusDownId(cardId == View.NO_ID ? firstId : cardId);
        }
        if (actionSettings != null) {
            actionSettings.setNextFocusLeftId(actionApps == null ? actionSettings.getId() : actionApps.getId());
            actionSettings.setNextFocusRightId(actionSettings.getId());
            actionSettings.setNextFocusUpId(actionSettings.getId());
            actionSettings.setNextFocusDownId(cardId == View.NO_ID ? firstId : cardId);
        }
        if (hasLive) {
            int id = liveDeck.root.getId();
            liveDeck.root.setNextFocusUpId(statusBlock == null ? id : statusBlock.getId());
            liveDeck.root.setNextFocusLeftId(id);
            liveDeck.root.setNextFocusRightId(hasPlex ? plexDeck.root.getId() : id);
            liveDeck.root.setNextFocusDownId(firstId);
        }
        if (hasPlex) {
            int id = plexDeck.root.getId();
            plexDeck.root.setNextFocusUpId(actionSettings == null ? id : actionSettings.getId());
            plexDeck.root.setNextFocusLeftId(hasLive ? liveDeck.root.getId() : id);
            plexDeck.root.setNextFocusRightId(id);
            plexDeck.root.setNextFocusDownId(d > 0 ? dockCards.get(d - 1).getId() : firstId);
        }
        for (int i = 0; i < d; i++) {
            View card = dockCards.get(i);
            // Left half of the pill rises to the live card, right half to the Plex card.
            boolean leftSide = i * 2 < d;
            int up = leftSide && hasLive ? liveDeck.root.getId()
                    : !leftSide && hasPlex ? plexDeck.root.getId()
                    : cardId != View.NO_ID ? cardId
                    : statusBlock == null ? firstId : statusBlock.getId();
            card.setNextFocusUpId(up);
            card.setNextFocusLeftId(i == 0 ? card.getId() : dockCards.get(i - 1).getId());
            card.setNextFocusRightId(i == d - 1 ? card.getId() : dockCards.get(i + 1).getId());
            card.setNextFocusDownId(c > 0 && cwOpen
                    ? cwCards.get(Math.min(i, c - 1)).getId()
                    : g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : card.getId());
        }
        for (int i = 0; i < c; i++) {
            View tile = cwCards.get(i);
            tile.setNextFocusUpId(d > 0 ? dockCards.get(Math.min(i, d - 1)).getId() : tile.getId());
            tile.setNextFocusLeftId(i == 0 ? tile.getId() : cwCards.get(i - 1).getId());
            tile.setNextFocusRightId(i == c - 1 ? tile.getId() : cwCards.get(i + 1).getId());
            tile.setNextFocusDownId(tile.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View card = gridCards.get(j);
            int r = j / cols, col = j % cols;
            card.setNextFocusUpId(r == 0
                    ? d > 0 ? dockCards.get(Math.min(col, d - 1)).getId()
                      : statusBlock == null ? card.getId() : statusBlock.getId()
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

    /**
     * Cold start lands on the first app. The backdrop is the first focusable view in the tree, so a
     * cold start can hand it the cursor - and then the first OK opens a live channel instead of the
     * app under the viewer's eye. Only runs while they have pressed nothing themselves.
     */
    private void settleFirstFocus() {
        if (userNavigated || dockCards.isEmpty()) return;
        if (dockCards.contains(getCurrentFocus())) return;
        dockCards.get(0).requestFocus();
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
    /** OK pressed: the tile gives under the press, then springs back to its focused size. */
    private void pressDip(View v, float base) {
        v.animate().scaleX(base * 0.94f).scaleY(base * 0.94f).setDuration(90)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> v.animate().scaleX(base).scaleY(base)
                        .setInterpolator(HomeTheme.settle()).setDuration(HomeTheme.MS_REVEAL).start())
                .start();
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
        // The sheet's own glass, not a grey phone menu: near-black, a hairline, an 18dp corner.
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xFA0E0E10);
        bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(232), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this) && !t.getId().startsWith("com.amazon.") && !t.getId().toLowerCase().contains("arcinput")) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) rows.add(inputRow(inputLabel(t), () -> switchInput(t)));
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(44)));   // a TV row, not a phone row
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
        pw.setOnDismissListener(() -> {
            inputsPopup = null;
            if (actionSettings != null) actionSettings.requestFocus();   // back to the pill that opened it
        });
        inputsPopup = pw;
        // Anchor to the pill, never to the row that asked: the Settings sheet is hidden by the time
        // this opens, and a popup anchored to a hidden view lands on its stale coordinates - in the
        // middle of the home screen. (That is exactly how the Cube showed it.)
        View pill = actionSettings != null && actionSettings.isAttachedToWindow() ? actionSettings : anchor;
        if (pill.isAttachedToWindow()) pw.showAsDropDown(pill, 0, dp(8), Gravity.END);
        else pw.showAtLocation(page, Gravity.TOP | Gravity.END, dp(HomeTheme.SAFE), dp(HomeTheme.ACTION_TOP + 60));
        rows.get(0).requestFocus();
    }

    /**
     * Hands a passthrough input to whichever app tunes it. Fire OS's player can still refuse it
     * ("No tune target found", which is what the Cube does for its phantom HDMI1): the intent
     * starts, nothing appears, and the screen is ours again. So look again once it has had its
     * chance and say out loud that the input did not come up.
     */
    private void switchInput(TvInputInfo info) {
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!launch(i)) {
            if (inputPickerPkg.isEmpty() || !openPackage(inputPickerPkg))
                android.widget.Toast.makeText(this, inputLabel(info) + " can't be opened from here",
                        android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final String label = inputLabel(info);
        handler.postDelayed(() -> {
            if (hasWindowFocus())
                android.widget.Toast.makeText(this, label + " isn't available on this device",
                        android.widget.Toast.LENGTH_LONG).show();
        }, 1800);
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(Typo.CAPTION, "sans-serif", Color.WHITE);   // nothing on this screen is below a caption
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        // The same focus as the Settings rows: a lighter pane of the same glass, never an inverted
        // white bar (which is what made a one-row popup look like a search box).
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x1FFFFFFF : Color.TRANSPARENT);
            r.setTextColor(Color.WHITE);
        });
        return r;
    }

    private void updateClock() {
        // Digits only, in the device's own zone: the meridian is noise on a screen this quiet.
        long now = System.currentTimeMillis() / 1000L;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        rollClock(Times.clockDigits(now, android.text.format.DateFormat.is24HourFormat(this), zone));
        if (greet != null) greet.setText(Typo.hello(Times.greeting(now, zone), familyName));
        if (dateLine != null) dateLine.setText(Times.dateLine(now, zone));
    }

    /**
     * What the pill can honestly say about a wired link.
     *
     * The negotiated rate lives in /sys/class/net/<iface>/speed, which Fire OS 7 denies to apps and
     * the shell alike (measured on the Cube: EACCES); a firmware that allows it gets "Link 1 Gbps".
     * Next is a rate the snapshot supplies (eth_mbps, e.g. the router's port). Fire OS's own network
     * agent advertises a flat 100 Mbps for every Ethernet link, so that number is a floor and only a
     * higher one is printed. A recognised USB adapter is a rating, not a negotiated link - an RTL8153
     * on a 100 Mbps switch is still a "gigabit adapter", and the Cube's USB 2.0 port carries about
     * 300 Mbps whatever the adapter - so it says so: "Gigabit adapter".
     */
    private String ethernetDetail() {
        int direct = ethernetMbpsSysfs();
        if (direct > 0) return "Link " + linkRate(direct);
        int fromHa = (int) number(haEthMbps, 0);
        if (fromHa > 0) return "Link " + linkRate(fromHa);
        int usb = usbEthernetMbps();
        if (usb > 0) return (usb == 1000 ? "Gigabit" : linkRate(usb)) + " adapter";
        int capability = ethernetMbpsCapability();
        return capability > 100 ? "Link " + linkRate(capability) : "";
    }

    /** What the Ethernet network agent advertises, in Mbps. Often a floor; see {@link #ethernetMbps}. */
    private int ethernetMbpsCapability() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
                        continue;
                    int kbps = Math.max(nc.getLinkDownstreamBandwidthKbps(),
                            nc.getLinkUpstreamBandwidthKbps());
                    if (kbps > 0) return Math.max(1, Math.round(kbps / 1000f));
                }
            }
        } catch (RuntimeException ignored) { }
        return 0;
    }

    /**
     * A wired USB adapter's own class, from its vendor/product ids. No permission is needed to
     * enumerate devices, and the ids are what the kernel matches drivers on, so this is stable
     * across names and firmware. Only parts faster than the advertised 100 Mbps are listed.
     */
    private int usbEthernetMbps() {
        try {
            android.hardware.usb.UsbManager um =
                    (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);
            if (um == null) return 0;
            int best = 0;
            for (android.hardware.usb.UsbDevice dev : um.getDeviceList().values()) {
                int vendor = dev.getVendorId(), product = dev.getProductId();
                if (vendor == 0x0BDA) {                          // Realtek
                    if (product == 0x8153) best = Math.max(best, 1000);   // RTL8153 gigabit
                    else if (product == 0x8156) best = Math.max(best, 2500);   // RTL8156 2.5G
                } else if (vendor == 0x0B95 && product == 0x1790) {   // ASIX AX88179
                    best = Math.max(best, 1000);
                }
            }
            return best;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** "1 Gbps", "2.5 Gbps", "866 Mbps": a link rate the way a router prints it. */
    private static String linkRate(int mbps) {
        if (mbps < 1000) return mbps + " Mbps";
        if (mbps % 1000 == 0) return (mbps / 1000) + " Gbps";
        return String.format(Locale.US, "%.1f Gbps", mbps / 1000f);
    }

    /** The interface's own /sys speed, where the firmware lets an app read it. */
    private static int ethernetMbpsSysfs() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> all = java.net.NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                java.net.NetworkInterface iface = all.nextElement();
                String name = iface.getName();
                if (name == null || !name.startsWith("eth") || !iface.isUp()) continue;
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader("/sys/class/net/" + name + "/speed"));
                try {
                    int mbps = Integer.parseInt(r.readLine().trim());
                    if (mbps > 0) return mbps;
                } catch (Exception ignored) {
                } finally {
                    try { r.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private boolean isEthernet() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected()
                && ni.getType() == android.net.ConnectivityManager.TYPE_ETHERNET;
    }

    private void setNet(String label) {
        netLabel = label;
        if (netChip == null) return;
        int icon = isEthernet() ? R.drawable.ic_ethernet : R.drawable.ic_wifi;
        netChip.setTag(icon);
        paintChip(netChip, icon, netChip.isFocused(), netLabel);
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_ETHERNET) {
            String detail = ethernetDetail();
            setNet(detail.isEmpty() ? "Ethernet" : "Ethernet  \u00B7  " + detail);
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Offline";
                else if (wm.getConnectionInfo().getLinkSpeed() > 0)
                    name += "  \u00B7  Link " + wm.getConnectionInfo().getLinkSpeed() + " Mbps";
            }
        } catch (SecurityException ignored) { }
        setNet(name);
    }

    /** The apps grid sits over the busiest part of the photo; browsing it deserves a floor. */
    private void dimBackdrop(boolean dim) {
        gridShade.animate().alpha(dim ? 1f : 0f).setDuration(HomeTheme.MS_REVEAL).start();
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

    /** The minute turns over like the Lock Screen's: the old time lifts away, the new one rises in. */
    private void rollClock(String digits) {
        CharSequence was = clock.getText();
        if (digits.contentEquals(was)) return;
        if (was.length() == 0 || !clock.isShown()) {
            clock.setText(digits);
            return;
        }
        clock.animate().alpha(0f).translationY(-dp(4)).setDuration(HomeTheme.MS_FOCUS)
                .setInterpolator(HomeTheme.ease()).withLayer().withEndAction(() -> {
                    clock.setText(digits);
                    clock.setTranslationY(dp(4));
                    clock.animate().alpha(1f).translationY(0).setDuration(HomeTheme.MS_REVEAL)
                            .setInterpolator(HomeTheme.ease()).withLayer().start();
                }).start();
    }

    /**
     * The glass takes a little of the photo's colour (see {@link HomeTheme#glassFor}) and eases over
     * with the photo's own crossfade. The colour is saved so the Home-key cover's dock matches.
     */
    private void tintGlass(Bitmap bm) {
        Bitmap small = Bitmap.createScaledBitmap(bm, 16, 9, true);
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < 9; y++) for (int x = 0; x < 16; x++) {
            int p = small.getPixel(x, y);
            r += Color.red(p); g += Color.green(p); b += Color.blue(p);
        }
        if (small != bm) small.recycle();
        int to = HomeTheme.glassFor(Color.rgb((int) (r / 144), (int) (g / 144), (int) (b / 144)));
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putInt("glass", to).apply();
        ValueAnimator a = ValueAnimator.ofArgb(glassColor, to);
        a.setDuration(2000);
        a.addUpdateListener(an -> {
            glassColor = (int) an.getAnimatedValue();
            for (GradientDrawable gd : glasses) gd.setColor(glassColor);
            for (TextView c : chips)
                if (!c.isFocused()) ((GradientDrawable) c.getBackground()).setColor(glassColor);
        });
        a.start();
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
        tintGlass(bm);
        // One small blurred copy per photo, for the frosted panel to sample.
        new Thread(() -> {
            final Bitmap f = Blur.frost(bm, 160, 90);
            runOnUiThread(() -> { if (haFrost != null) haFrost.setBlur(f); });
        }).start();
        wallFront.setAlpha(0f);
        wallFront.setScaleX(1f);
        wallFront.setScaleY(1f);
        // The slow zoom moves the photo about 0.2px a second, so it steps once a second instead of
        // animating every frame: a per-frame animator redrew the whole screen 60 times a second at
        // idle (measured on the Cube) and left focus moves no GPU headroom. Stops with its photo.
        final ImageView kb = wallFront;
        final long driftFrom = SystemClock.elapsedRealtime();
        handler.post(new Runnable() {
            @Override public void run() {
                if (kb != wallFront || isDestroyed()) return;
                float t = Math.min(1f, (SystemClock.elapsedRealtime() - driftFrom)
                        / (float) (WALLPAPER_INTERVAL_MS + 4000));
                kb.setScaleX(1f + 0.07f * t);
                kb.setScaleY(1f + 0.07f * t);
                if (t < 1f) handler.postDelayed(this, 1000);
            }
        });
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

    /**
     * Top to bottom, one beat apart: the clock, the actions, each card, the glass, then the dock's
     * tiles left to right. Object animators, not view.animate(): a start delay set there sticks to
     * the view and would hold back every focus lift that follows.
     */
    private void playEntrance() {
        int beat = 0;
        if (statusBlock != null) rise(statusBlock, dp(8), beat++);
        if (topActions != null) rise(topActions, dp(8), beat++);
        for (Deck dk : decks()) if (dk != null && dk.showing()) rise(dk.root, dp(8), beat++);
        if (dockPillRow != null) rise(dockPillRow, dp(16), beat++);
        // Tiles fade only: the focused one is already lifted, and its lift is a translation.
        for (int i = 0; i < dockCards.size(); i++)
            rise(dockCards.get(i), 0, beat + i * 0.5f);
    }

    private void rise(View v, float fromY, float beat) {
        v.setAlpha(0f);
        ObjectAnimator a = fromY == 0
                ? ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f)
                : ObjectAnimator.ofPropertyValuesHolder(v,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, fromY, 0f));
        a.setStartDelay(Math.round(beat * HomeTheme.STAGGER));
        a.setDuration(HomeTheme.MS_FADE);
        a.setInterpolator(HomeTheme.ease());
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator an) { v.setLayerType(View.LAYER_TYPE_HARDWARE, null); }
            @Override public void onAnimationEnd(Animator an) { v.setLayerType(View.LAYER_TYPE_NONE, null); }
        });
        a.start();
    }

    /** Last horizontal D-pad step, -1 left or +1 right: which way a newly focused tile leans. */
    private int lastStep;
    /** When the remote was last pressed, and how long the cards wait after it before turning. */
    private long lastKeyAt;
    private static final long KEY_QUIET_MS = 1200;

    /**
     * The focus lift the dock and the apps grid share: a small overshoot in, an ease out, and on a
     * sideways move a lean of {@link HomeTheme#TILT} degrees that levels out behind it.
     */
    private void lift(View v, boolean has, float scale) {
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .translationY(has ? -dp(HomeTheme.DOCK_FOCUS_LIFT) : 0)
                .setInterpolator(has ? HomeTheme.settle() : HomeTheme.ease())
                .setDuration(HomeTheme.MS_FOCUS).start();
        if (has && lastStep != 0) {
            v.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
            ObjectAnimator t = ObjectAnimator.ofFloat(v, View.ROTATION_Y, -lastStep * HomeTheme.TILT, 0f);
            t.setDuration(HomeTheme.MS_TILT);
            t.setInterpolator(HomeTheme.ease());
            t.start();
        } else if (!has) {
            v.setRotationY(0f);
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
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            lastStep = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            lastKeyAt = SystemClock.uptimeMillis();
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) userNavigated = true;
            View now = getCurrentFocus();
            // Down from the pill opens the hidden row. Routed here, not by nextFocusDownId, because
            // the row is GONE at rest and focus search cannot find a view that is not there.
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && dockCards.contains(now)
                    && !cwCards.isEmpty()) {
                revealContinue();
                return true;
            }
            // Up from a resume tile puts the row away and lands back on the pill.
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && cwCards.contains(now)) {
                hideContinue();
                if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
                return true;
            }
            // Up from the pill lands on the card directly above it, not in the status block.
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && dockCards.contains(now)) {
                int i = dockCards.indexOf(now);
                Deck up = i * 2 < dockCards.size()
                        ? (liveDeck != null && liveDeck.showing() ? liveDeck : plexDeck)
                        : (plexDeck != null && plexDeck.showing() ? plexDeck : liveDeck);
                if (up != null && up.showing()) {
                    up.root.requestFocus();
                    return true;
                }
            }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_SETTINGS && e.getAction() == KeyEvent.ACTION_UP) {
            showSettings();
            return true;
        }
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
