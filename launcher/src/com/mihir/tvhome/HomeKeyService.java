package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Fire OS will not let a third-party launcher own Home, so this catches the Home key and opens ours. */
public class HomeKeyService extends AccessibilityService {
    static volatile boolean running;
    private static HomeKeyService instance;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View cover;

    // The cover is built inside an accessibility callback, so nothing here may decode a bitmap or list
    // assets on the spot: one full-size wallpaper decode is the whole hitch on a Home press. Painted
    // ahead of time instead, and only ever read (never written) from showCover.
    private String[] wallNames;
    private static Bitmap coverWall;
    private static int coverWallIndex = -1;
    private static boolean coverDecoding;
    /** The dock the cover draws when the launcher's own list is not in memory yet. */
    private List<MainActivity.App> coverDock;
    /** The last hero still, decoded once in the background so a Home press never waits on it. */
    private static Bitmap coverHero;
    private static boolean heroDecoding;

    @Override protected void onServiceConnected() {
        running = true; instance = this; Log.w("TvHome", "HomeKeyService connected");
        try { wallNames = getAssets().list("wallpapers"); } catch (Exception ignored) { }
        loadDock();
        prefetchWallpaper();
        prefetchHero();
    }

    @Override public boolean onUnbind(Intent intent) { running = false; instance = null; hideCover(); return super.onUnbind(intent); }

    /** Put our accessibility service back after something turned it off. Android disables a package's
     *  accessibility services when that package is updated, which silently kills Home interception until the
     *  user finds the toggle again; this write is the one thing that brings it back (WRITE_SECURE_SETTINGS is
     *  granted over ADB by the install script). */
    static void rearm(Context c) {
        try {
            ComponentName me = new ComponentName(c, HomeKeyService.class);
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String list = withService(cur, me);
            // Clear then set: the accessibility manager only re-reads the list when the value actually changes.
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) { }
    }

    /** The accessibility list is "pkg/cls:pkg/cls:…". Ours goes on the end; anything else stays enabled.
     *  Entries may be written in either long or short form, so both are recognised. */
    private static String withService(String list, ComponentName comp) {
        String longForm = comp.flattenToString();
        String shortForm = comp.flattenToShortString();
        if (list == null) return longForm;
        String cur = list.trim();
        if (cur.isEmpty()) return longForm;
        for (String s : cur.split(":")) {
            String entry = s.trim();
            if (entry.equals(longForm) || entry.equals(shortForm)) return cur;   // already listed
        }
        return cur + ":" + longForm;
    }

    /** Amazon's launcher is the system Home and cannot be disabled, so bounce it to ours whenever it appears. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
        Log.w("TvHome", "window " + pkg + "/" + cls + " lag=" + (SystemClock.uptimeMillis() - event.getEventTime()) + "ms");
        if ("com.mihir.tvhome".contentEquals(pkg) && cls.toString().endsWith("MainActivity")) { hideCover(); return; }
        if (!"com.amazon.tv.launcher".contentEquals(pkg) || !cls.toString().contains("HomeActivity")) return;
        if (SystemClock.elapsedRealtime() < MainActivity.suppressRedirectUntil) return;
        Log.w("TvHome", "bounce Amazon home -> ours");
        showCover();
        goHome();
    }

    private void goHome() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    @Override public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        Log.w("TvHome", "key " + e.getKeyCode() + " a=" + e.getAction() + " src=" + e.getSource() + " dev=" + e.getDeviceId());
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        Log.w("TvHome", "HOME key action=" + e.getAction());
        if (e.getAction() == KeyEvent.ACTION_UP) {
            MainActivity.suppressRedirectUntil = 0;
            goHome();
        }
        return true;
    }

    // Android blocks background activity starts for ~5 s after Home is pressed, so hide Amazon's home meanwhile.
    static void coverGone() { if (instance != null) instance.hideCover(); }

    // ------------------------------------------------------------------ wallpaper, decoded in advance

    /** Decode the photo the launcher is showing, on a background thread, so the cover can draw it instantly. */
    private void prefetchWallpaper() {
        final int idx = wallpaperIndex();
        if (idx < 0 || coverDecoding || idx == coverWallIndex) return;
        coverDecoding = true;
        final String name = "wallpapers/" + wallNames[idx];
        new Thread(() -> {
            Bitmap bm = null;
            try (InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) { }
            final Bitmap done = bm;
            ui.post(() -> {
                if (done != null) { coverWall = done; coverWallIndex = idx; }
                coverDecoding = false;
            });
        }).start();
    }

    private int wallpaperIndex() {
        if (wallNames == null || wallNames.length == 0) return -1;
        int i = getSharedPreferences("MainActivity", MODE_PRIVATE).getInt("wallpaper", 0);
        int n = wallNames.length;
        return ((i % n) + n) % n;   // the launcher writes -1 before its first rotation
    }

    /**
     * The dock as the launcher would draw it: same saved order, same banner-then-icon source. Looked
     * up once when the service connects, because nothing may touch the package manager on a Home
     * press. When the launcher is alive it publishes its own list and this is only a fallback.
     */
    private void loadDock() {
        List<String> order = new ArrayList<>();
        String saved = getSharedPreferences("launcher", MODE_PRIVATE).getString("dock_order", null);
        if (saved != null) {
            for (String s : saved.split(",")) if (!s.isEmpty()) order.add(s);
        } else {
            order.addAll(Arrays.asList(getResources().getStringArray(R.array.dock_apps)));
        }
        List<MainActivity.App> apps = new ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (String p : order) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                MainActivity.App a = new MainActivity.App();
                a.pkg = p;
                a.label = String.valueOf(pm.getApplicationLabel(ai));
                Drawable banner = ai.loadBanner(pm);
                a.isBanner = banner != null;
                a.art = banner != null ? banner : ai.loadIcon(pm);
                // The launcher samples a colour from the art; a neutral wash is close enough for the
                // split second this fallback is on screen, and sampling would decode bitmaps here.
                a.color = 0xFF4A4F5A;
                apps.add(a);
            } catch (Exception ignored) { }
        }
        coverDock = apps;
    }

    /** Decode the hero still the launcher cached, off the UI thread, so the cover has art instantly. */
    private void prefetchHero() {
        final java.io.File f = new java.io.File(getCacheDir(), "hero_cover.jpg");
        if (!f.isFile() || heroDecoding) return;
        heroDecoding = true;
        new Thread(() -> {
            Bitmap bm = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            } catch (Exception ignored) { }
            final Bitmap done = bm;
            ui.post(() -> { if (done != null) coverHero = done; heroDecoding = false; });
        }).start();
    }

    // ------------------------------------------------------------------ the cover

    /**
     * The screen the viewer sees while Android refuses our activity start. It is not a logo or a
     * loading screen: it is the home screen with the data already known - the same bar, the last
     * hero still, the real dock icons and the shape of the resume row - so Home reads as one screen
     * settling rather than three.
     */
    private void showCover() {
        if (cover != null) return;
        try {
            final float d = getResources().getDisplayMetrics().density;
            prefetchWallpaper();
            FrameLayout f = new FrameLayout(this);
            f.setBackgroundColor(Color.BLACK);

            // The still the launcher is showing, borrowed from its process (same app). If it has
            // one, the cover gets the hero's own gradients; if not, the wallpaper and its wash.
            Bitmap hero = MainActivity.currentHero != null ? MainActivity.currentHero : coverHero;
            Bitmap photo = hero != null ? hero
                    : MainActivity.currentWall != null ? MainActivity.currentWall : coverWall;
            if (photo != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(photo);
                f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }

            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (hero == null) {
                View scrim = new View(this);
                scrim.setBackground(HomeTheme.wallpaperScrim());
                f.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

                View tint = new View(this);
                tint.setBackgroundColor(HomeTheme.timeTint(hour));
                f.addView(tint, new FrameLayout.LayoutParams(-1, -1));

                View glow = new View(this);    // the launcher's ambient colour behind the dock
                GradientDrawable g = new GradientDrawable();
                g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
                g.setGradientCenter(0.5f, 0.78f);
                g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
                int gc = MainActivity.glowNow;
                g.setColors(new int[]{HomeTheme.withAlpha(gc, 0.5f), HomeTheme.withAlpha(gc, 0.14f),
                        Color.TRANSPARENT});
                glow.setBackground(g);
                f.addView(glow, new FrameLayout.LayoutParams(-1, -1));
            } else {
                View top = new View(this);
                top.setBackground(HomeTheme.topScrim());
                f.addView(top, new FrameLayout.LayoutParams(-1, px(d, HomeTheme.TOP_SCRIM_H)));
                View left = new View(this);
                left.setBackground(HomeTheme.leftScrim());
                f.addView(left, new FrameLayout.LayoutParams(-1, -1));
                View bottom = new View(this);
                bottom.setBackground(HomeTheme.bottomScrim());
                f.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
            }

            View dockFade = new View(this);
            dockFade.setBackground(HomeTheme.dockFade());
            f.addView(dockFade, new FrameLayout.LayoutParams(-1, px(d, 230), Gravity.BOTTOM));

            addTopBar(f, d);
            addBottom(f, d);
            addSpinner(f, d);

            f.setFocusable(true);
            f.setOnKeyListener((v, k, e) -> true);   // swallow the remote while we load

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.graphics.PixelFormat.OPAQUE);
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(f, lp);
            f.requestFocus();
            cover = f;
            ui.postDelayed(this::goHome, 5500);      // Android may have dropped the first start; ask again
            ui.postDelayed(this::goHome, 7500);
            ui.postDelayed(this::hideCover, 10000);   // never leave it up if the launcher fails to open
        } catch (Exception e) { Log.w("TvHome", "cover failed: " + e); }
    }

    /** The top bar as the launcher draws it: greeting, glance strip, clock, icon-only chips. */
    private void addTopBar(final FrameLayout f, final float d) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String family = getString(R.string.family_name);
        String hello = hour < 5 ? "Good night" : hour < 12 ? "Good morning"
                : hour < 17 ? "Good afternoon" : "Good evening";
        // The cover must pick the same string the launcher will: with a strip present the full name
        // does not fit on this width, and home shows the short greeting there.

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        boolean glance = addGlance(right, d);

        TextView g = new TextView(this);
        g.setText(family.isEmpty() || glance ? hello : hello + ", " + family);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.BODY);
        g.setTypeface(Typo.medium(this));
        g.setTextColor(HomeTheme.TEXT_70);
        g.setSingleLine(true);
        g.setEllipsize(TextUtils.TruncateAt.END);
        g.setShadowLayer(10 * d, 0, d, 0xB3000000);
        bar.addView(g, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView clk = new TextView(this);
        clk.setText(Times.clockTime(System.currentTimeMillis() / 1000L, is24(), java.time.ZoneId.systemDefault()));
        clk.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.CLOCK_BAR);
        clk.setTypeface(Typo.light(this));
        clk.setTextColor(Color.WHITE);
        clk.setIncludeFontPadding(false);
        clk.setFontFeatureSettings("tnum");
        clk.setShadowLayer(10 * d, 0, d, 0xB3000000);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMargins(px(d, 32), 0, 0, 0);
        right.addView(clk, clp);

        if (hasPassthroughInputs()) right.addView(chip(R.drawable.ic_input, d), chipParams(d));
        right.addView(chip(isEthernet() ? R.drawable.ic_ethernet : R.drawable.ic_wifi, d), chipParams(d));
        right.addView(chip(R.drawable.ic_settings, d), chipParams(d));

        bar.addView(right, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(d, HomeTheme.TOP_BAR_H), Gravity.TOP);
        lp.setMargins(px(d, HomeTheme.SAFE), px(d, HomeTheme.TOP_BAR_TOP), px(d, HomeTheme.SAFE), 0);
        f.addView(bar, lp);

        // A cover can outlive the minute it was built in.
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (cover == null) return;
                clk.setText(Times.clockTime(System.currentTimeMillis() / 1000L, is24(),
                        java.time.ZoneId.systemDefault()));
                ui.postDelayed(this, 10_000);
            }
        };
        ui.postDelayed(tick, 10_000);
    }

    private boolean is24() {
        return android.text.format.DateFormat.is24HourFormat(this);
    }

    /** The same four values, separators and staleness rule as the launcher's strip. */
    private boolean addGlance(LinearLayout right, float d) {
        String json = getSharedPreferences("launcher", MODE_PRIVATE).getString("ha_json", "");
        if (json.isEmpty()) return false;
        try {
            JSONObject j = new JSONObject(json);
            JSONObject ages = j.optJSONObject("ages");
            String temp = j.optString("temp", "");
            String solar = j.optString("solar", "");
            String battery = j.optString("battery", "");
            String bins = Cards.binsGlance(j.optString("bins", ""));
            String[] values = {
                    temp.isEmpty() ? "" : temp + "\u00B0",
                    solar.isEmpty() ? "" : Times.kw(solar) + " kW",
                    battery.isEmpty() ? "" : battery + "%",
                    bins
            };
            String[] keys = {"temp", "solar", "battery", "bins"};
            int[] icons = {R.drawable.ic_thermo, R.drawable.ic_solar,
                    R.drawable.ic_battery, R.drawable.ic_bins};

            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setPadding(px(d, 6), px(d, 4), px(d, 6), px(d, 4));
            boolean any = false;
            for (int i = 0; i < values.length; i++) {
                if (values[i].isEmpty()) continue;
                LinearLayout item = new LinearLayout(this);
                item.setGravity(Gravity.CENTER_VERTICAL);
                if (any) {
                    TextView sep = new TextView(this);
                    sep.setText("\u00B7");
                    sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.CAPTION);
                    sep.setTextColor(0x66FFFFFF);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
                    sp.setMargins(px(d, 8), 0, px(d, 6), 0);   // ~24dp between values, dot included
                    item.addView(sep, sp);
                }
                ImageView icon = new ImageView(this);
                icon.setImageResource(icons[i]);
                icon.setImageTintList(ColorStateList.valueOf(0x99FFFFFF));
                item.addView(icon, new LinearLayout.LayoutParams(px(d, 16), px(d, 16)));
                TextView value = new TextView(this);
                value.setText(values[i]);
                value.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.BODY);
                value.setTypeface(Typo.medium(this));
                value.setTextColor("bins".equals(keys[i]) ? HomeTheme.ACCENT : Color.WHITE);
                value.setSingleLine(true);
                value.setFontFeatureSettings("tnum");
                value.setShadowLayer(8 * d, 0, d, 0x99000000);
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
                vp.setMargins(px(d, 4), 0, 0, 0);
                item.addView(value, vp);
                long age = ages == null ? 0 : ages.optLong(keys[i], 0);
                item.setAlpha(Cards.glanceAlpha(age));
                strip.addView(item, new LinearLayout.LayoutParams(-2, -2));
                any = true;
            }
            if (any) right.addView(strip, new LinearLayout.LayoutParams(-2, px(d, 44)));
            return any;
        } catch (Exception ignored) { return false; }
    }

    private TextView chip(int iconRes, float d) {
        TextView p = new TextView(this);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.CAPTION);
        p.setTypeface(Typo.medium(this));
        p.setTextColor(0xE6FFFFFF);
        p.setGravity(Gravity.CENTER);
        p.setPadding(px(d, 6), 0, px(d, 6), 0);
        p.setMinWidth(px(d, 30));
        Drawable ic = getDrawable(iconRes);
        ic.setBounds(0, 0, px(d, 18), px(d, 18));
        p.setCompoundDrawables(ic, null, null, null);
        p.setCompoundDrawableTintList(ColorStateList.valueOf(0xE6FFFFFF));
        return p;
    }

    private LinearLayout.LayoutParams chipParams(float d) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, px(d, 40));
        p.setMargins(px(d, 6), 0, 0, 0);
        return p;
    }

    /** Dock, then the resume row: the same stack, order and margins as the launcher's bottom block. */
    private void addBottom(FrameLayout f, float d) {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);   // the first tile's 1.08 focus scale must be allowed to overhang
        dock.setPadding(px(d, HomeTheme.DOCK_PAD_H), px(d, HomeTheme.DOCK_PAD_V),
                px(d, HomeTheme.DOCK_PAD_H), px(d, HomeTheme.DOCK_PAD_V));
        List<MainActivity.App> apps = MainActivity.dockSnapshot != null ? MainActivity.dockSnapshot : coverDock;
        if (apps != null) {
            for (int i = 0; i < apps.size(); i++) {
                FrameLayout card = AppTile.card(this, apps.get(i), d);
                // The launcher hands the cursor to the first tile on a cold start, so that tile is
                // already at 1.08 and lifted. Drawing it flat made the dock jump by 8% on the swap.
                if (i == 0) {
                    card.setScaleX(HomeTheme.DOCK_FOCUS_SCALE);
                    card.setScaleY(HomeTheme.DOCK_FOCUS_SCALE);
                    card.setTranslationY(-px(d, HomeTheme.DOCK_FOCUS_LIFT));
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
                p.setMargins(i == 0 ? 0 : px(d, HomeTheme.DOCK_GAP), 0,
                        i == apps.size() - 1 ? 0 : px(d, HomeTheme.DOCK_GAP), 0);
                dock.addView(card, p);
            }
        }
        bottom.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        int count = getSharedPreferences("launcher", MODE_PRIVATE).getInt("cw_count", 0);
        if (count > 0) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);

            TextView head = new TextView(this);
            head.setText("Continue Watching");
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.SECTION);
            head.setTypeface(Typo.semibold(this));
            head.setTextColor(0xE6FFFFFF);
            head.setShadowLayer(10 * d, 0, d, 0x99000000);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
            hp.setMargins(px(d, HomeTheme.SAFE), 0, 0, px(d, HomeTheme.CW_HEAD_GAP));
            block.addView(head, hp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(px(d, HomeTheme.SAFE), 0, px(d, HomeTheme.SAFE), 0);
            int n = Math.min(3, count);
            for (int i = 0; i < n; i++) {
                View tile = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(px(d, HomeTheme.CW_RADIUS));
                bg.setColor(0x14FFFFFF);   // white 8%: a shape, not a fake poster
                tile.setBackground(bg);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        px(d, HomeTheme.CW_W), px(d, HomeTheme.CW_H));
                p.setMargins(i == 0 ? 0 : px(d, HomeTheme.CW_GAP), 0, 0, 0);
                row.addView(tile, p);
            }
            // The same fixed height the launcher's scroll view uses, or the dock above sits higher.
            block.addView(row, new LinearLayout.LayoutParams(-1, px(d, HomeTheme.CW_H)));

            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = px(d, HomeTheme.DOCK_TO_CW);
            bottom.addView(block, bp);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.bottomMargin = px(d, HomeTheme.CW_BOTTOM);
        f.addView(bottom, lp);
    }

    /** Only after 1.5s: for that long the cover is simply the home screen, and a spinner would spoil it. */
    private void addSpinner(FrameLayout f, float d) {
        final android.widget.ProgressBar spin = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(ColorStateList.valueOf(0x99FFFFFF));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(px(d, 24), px(d, 24),
                Gravity.BOTTOM | Gravity.END);
        p.setMargins(0, 0, px(d, HomeTheme.SAFE), px(d, HomeTheme.CW_BOTTOM));
        f.addView(spin, p);
        spin.setAlpha(0f);
        ui.postDelayed(() -> {
            if (cover == f) spin.animate().alpha(0.6f).setDuration(300).start();
        }, 1500);
    }

    private boolean isEthernet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_ETHERNET;
    }

    private boolean hasPassthroughInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return false;
        for (TvInputInfo t : tim.getTvInputList())
            if (t.isPassthroughInput() && !t.isHidden(this)
                    && !t.getId().startsWith("com.amazon.")
                    && !t.getId().toLowerCase(Locale.getDefault()).contains("arcinput")) return true;
        return false;
    }

    private static int px(float d, int dp) {
        return Math.round(dp * d);
    }

    private void hideCover() {
        ui.removeCallbacksAndMessages(null);
        final View c = cover;
        if (c == null) return;
        cover = null;
        c.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(c); } catch (Exception ignored) { }
        }).start();
    }
}
