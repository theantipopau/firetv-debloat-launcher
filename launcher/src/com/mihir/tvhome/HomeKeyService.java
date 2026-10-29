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
    /** Retries live on their own handler: hideCover() clears the cover's callbacks, not these. */
    private final Handler retry = new Handler(Looper.getMainLooper());
    private long pingStart;
    private final Runnable homePing = new Runnable() {
        @Override public void run() {
            if (cover == null) return;
            if (!MainActivity.visible) goHome();
            if (SystemClock.elapsedRealtime() - pingStart < 9000) retry.postDelayed(this, 600);
        }
    };
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

    @Override protected void onServiceConnected() {
        running = true; instance = this; Log.w("TvHome", "HomeKeyService connected");
        try { wallNames = getAssets().list("wallpapers"); } catch (Exception ignored) { }
        loadDock();
        prefetchWallpaper();
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
        startHomePing();
    }

    /**
     * Android refuses background activity starts for about five seconds after Home, and Amazon's own
     * home is already launching by then - one start can therefore lose the race and leave its page
     * on screen. So ask again, every 600ms, until the launcher is actually the visible activity; the
     * cover stays down meanwhile, which is why the retries are invisible.
     */
    private void startHomePing() {
        pingStart = SystemClock.elapsedRealtime();
        retry.removeCallbacks(homePing);
        goHome();
        retry.postDelayed(homePing, 600);
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
        // Put the cover up as the key goes down, before the system brings Amazon's home to the
        // front: waiting for its window event meant that page was visible for a moment first.
        if (e.getAction() == KeyEvent.ACTION_DOWN && !MainActivity.visible) showCover();
        if (e.getAction() == KeyEvent.ACTION_UP) {
            MainActivity.suppressRedirectUntil = 0;
            startHomePing();
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
                a.icon = ai.loadIcon(pm);
                a.isBanner = banner != null;
                a.art = banner != null ? banner : a.icon;
                // The launcher samples a colour from the art; a neutral wash is close enough for the
                // split second this fallback is on screen, and sampling would decode bitmaps here.
                a.color = 0xFF4A4F5A;
                apps.add(a);
            } catch (Exception ignored) { }
        }
        coverDock = apps;
    }

    // ------------------------------------------------------------------ the cover

    /**
     * The screen the viewer sees while Android refuses our activity start. It is not a logo or a
     * loading screen: it is the home screen with the data already known - the same clock in the same
     * place, skeleton lines where the values will appear, and the same glass pill of real icons at
     * the same size, gaps and focus scale - so Home reads as one screen settling.
     */
    private void showCover() {
        if (cover != null) return;
        try {
            final float d = getResources().getDisplayMetrics().density;
            prefetchWallpaper();
            FrameLayout f = new FrameLayout(this);
            f.setBackgroundColor(Color.BLACK);

            Bitmap photo = MainActivity.currentWall != null ? MainActivity.currentWall : coverWall;
            if (photo != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(photo);
                f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }

            // The launcher's two washes, from the same source, so the swap is invisible.
            View left = new View(this);
            left.setBackground(HomeTheme.leftWash());
            f.addView(left, new FrameLayout.LayoutParams(-1, -1));
            View bottom = new View(this);
            bottom.setBackground(HomeTheme.bottomWash());
            f.addView(bottom, new FrameLayout.LayoutParams(-1, px(d, 340), Gravity.BOTTOM));

            addStatus(f, d);
            addDock(f, d);
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

    /** The clock, then three skeleton lines in the shape the real ones take. */
    private void addStatus(final FrameLayout f, final float d) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(px(d, HomeTheme.BLOCK_PAD_H), px(d, HomeTheme.BLOCK_PAD_V),
                px(d, HomeTheme.BLOCK_PAD_H), px(d, HomeTheme.BLOCK_PAD_V));

        // The greeting is real text, not a bar: it needs nothing from the network, so the cover can
        // say exactly what the launcher will say a moment later.
        String family = getString(R.string.family_name);
        String hello = Times.greeting(System.currentTimeMillis() / 1000L,
                java.time.ZoneId.systemDefault());
        TextView g = new TextView(this);
        g.setText(family.isEmpty() ? hello : hello + " " + family);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        g.setTypeface(Typo.medium(this));
        g.setTextColor(0xD9FFFFFF);
        g.setIncludeFontPadding(false);
        g.setShadowLayer(px(d, 8), 0, px(d, 1), 0x80000000);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.bottomMargin = px(d, HomeTheme.GREET_GAP);
        block.addView(g, gp);

        final TextView clk = new TextView(this);
        clk.setText(Times.clockDigits(System.currentTimeMillis() / 1000L, is24(),
                java.time.ZoneId.systemDefault()));
        clk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        clk.setTypeface(Typo.light(this));
        clk.setTextColor(Color.WHITE);
        clk.setIncludeFontPadding(false);
        clk.setFontFeatureSettings("tnum");
        clk.setShadowLayer(px(d, 8), 0, px(d, 1), 0x80000000);
        block.addView(clk);

        // The date hangs under the clock in the same spot the launcher puts it.
        TextView day = new TextView(this);
        day.setText(Times.dateLine(System.currentTimeMillis() / 1000L,
                java.time.ZoneId.systemDefault()));
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        day.setTypeface(Typo.regular(this));
        day.setTextColor(0xB3FFFFFF);
        day.setIncludeFontPadding(false);
        day.setShadowLayer(px(d, 8), 0, px(d, 1), 0x80000000);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-2, -2);
        dp2.topMargin = px(d, HomeTheme.DATE_TOP);
        block.addView(day, dp2);

        LinearLayout bars = new LinearLayout(this);
        bars.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1, -2);
        box.topMargin = px(d, HomeTheme.CLOCK_GAP + 8);
        block.addView(bars, box);
        int[] widths = {300, 246, 190};
        for (int i = 0; i < widths.length; i++) {
            View bar = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(d, 7));
            bg.setColor(0x20FFFFFF);   // white 12%: a shape, never fake text
            bar.setBackground(bg);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(d, widths[i]), px(d, 14));
            p.topMargin = px(d, i == 0 ? 0 : HomeTheme.LINE_GAP + 6);
            bars.addView(bar, p);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                px(d, HomeTheme.NOW_W), -2, Gravity.TOP | Gravity.START);
        lp.setMargins(px(d, HomeTheme.SAFE), px(d, HomeTheme.CLOCK_TOP), 0, 0);
        f.addView(block, lp);

        // A cover can outlive the minute it was built in.
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (cover == null) return;
                clk.setText(Times.clockDigits(System.currentTimeMillis() / 1000L, is24(),
                        java.time.ZoneId.systemDefault()));
                ui.postDelayed(this, 10_000);
            }
        };
        ui.postDelayed(tick, 10_000);
    }

    /** One glass pill: the same tile size, gaps, names and first-slot lift as the launcher's. */
    private void addDock(FrameLayout f, float d) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClipChildren(false);
        pill.setClipToPadding(false);
        pill.setPadding(px(d, HomeTheme.DOCK_PAD), px(d, HomeTheme.DOCK_PAD_TOP),
                px(d, HomeTheme.DOCK_PAD), px(d, HomeTheme.DOCK_PAD_BOTTOM));
        pill.setBackground(HomeTheme.glassPill(HomeTheme.DOCK_PILL_RADIUS, d));

        List<MainActivity.App> apps = MainActivity.dockSnapshot != null
                ? MainActivity.dockSnapshot : coverDock;
        if (apps != null) {
            int gap = Math.max(0, px(d, HomeTheme.DOCK_GAP) - px(d, 12));
            for (int i = 0; i < apps.size(); i++) {
                FrameLayout slot = new FrameLayout(this);
                slot.setClipChildren(false);
                slot.setClipToPadding(false);
                slot.setPadding(px(d, 6), px(d, 3), px(d, 6), px(d, 9));   // same shift as the launcher

                View halo = new View(this);
                GradientDrawable gd = new GradientDrawable();
                gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
                gd.setGradientRadius(px(d, 74));
                gd.setGradientCenter(0.5f, 0.5f);
                gd.setColors(new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x00000000});
                halo.setBackground(gd);
                halo.setAlpha(0f);
                slot.addView(halo, new FrameLayout.LayoutParams(-1, -1));

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                FrameLayout frame = new FrameLayout(this);
                frame.addView(AppTile.dock(this, apps.get(i), d),
                        new FrameLayout.LayoutParams(-1, -1));
                col.addView(frame, new LinearLayout.LayoutParams(
                        px(d, HomeTheme.DOCK_W), px(d, HomeTheme.DOCK_H)));

                TextView name = new TextView(this);
                name.setText(apps.get(i).label);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, HomeTheme.DOCK_NAME);   // a label, not a caption
                name.setTypeface(Typo.medium(this));
                name.setTextColor(0xCCFFFFFF);
                name.setSingleLine(true);
                name.setGravity(Gravity.CENTER);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setShadowLayer(px(d, 6), 0, px(d, 1), 0x99000000);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                        px(d, HomeTheme.DOCK_W), -2);
                np.topMargin = px(d, HomeTheme.DOCK_NAME_GAP);
                col.addView(name, np);
                slot.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

                // The launcher hands the cursor to the first slot, so the cover draws it lifted.
                if (i == 0) {
                    slot.setScaleX(HomeTheme.DOCK_FOCUS_SCALE);
                    slot.setScaleY(HomeTheme.DOCK_FOCUS_SCALE);
                    halo.setAlpha(1f);
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        px(d, HomeTheme.DOCK_W + 12), px(d, HomeTheme.DOCK_SLOT_H));
                p.setMargins(i == 0 ? 0 : gap, 0, i == apps.size() - 1 ? 0 : gap, 0);
                pill.addView(slot, p);
            }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = px(d, HomeTheme.DOCK_BOTTOM);
        f.addView(pill, lp);
    }

    private boolean is24() {
        return android.text.format.DateFormat.is24HourFormat(this);
    }

    /** Only after 1.5s: for that long the cover is simply the home screen, and a spinner would spoil it. */
    /**
     * The loading line: "Please wait, Home loading…" with the wheel beside it, on the floor under the
     * glass pill. It used to sit at the pill's own height, where the glass's right end cut across the
     * wheel and the two read as one broken control.
     */
    private void addSpinner(FrameLayout f, float d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("Please wait, Home loading\u2026");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typo.medium(this));
        label.setTextColor(0xD9FFFFFF);
        label.setIncludeFontPadding(false);
        label.setShadowLayer(px(d, 6), 0, px(d, 1), 0x99000000);   // the wallpaper can be bright here
        row.addView(label);

        final android.widget.ProgressBar spin = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(ColorStateList.valueOf(0xD9FFFFFF));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(d, 20), px(d, 20));
        sp.setMargins(px(d, 10), 0, 0, 0);
        row.addView(spin, sp);

        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        p.setMargins(0, 0, px(d, HomeTheme.SAFE), px(d, HomeTheme.LOADING_BOTTOM));
        f.addView(row, p);
        row.setAlpha(0f);
        ui.postDelayed(() -> {
            if (cover == f) row.animate().alpha(0.8f).setDuration(300).start();
        }, 1200);
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
        retry.removeCallbacks(homePing);
        final View c = cover;
        if (c == null) return;
        cover = null;
        c.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(c); } catch (Exception ignored) { }
        }).start();
    }
}
