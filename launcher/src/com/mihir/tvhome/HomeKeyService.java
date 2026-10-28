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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
    private static int dockCount = -1;
    private int bandHeight;

    @Override protected void onServiceConnected() {
        running = true; instance = this; Log.w("TvHome", "HomeKeyService connected");
        try { wallNames = getAssets().list("wallpapers"); } catch (Exception ignored) { }
        countDock();
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

    /** How many dock tiles the launcher draws, so the band silhouette is the same size. Package lookups
     *  are done once here rather than on every Home press. */
    private void countDock() {
        List<String> order = new ArrayList<>();
        String saved = getSharedPreferences("launcher", MODE_PRIVATE).getString("dock_order", null);
        if (saved != null) {
            for (String s : saved.split(",")) if (!s.isEmpty()) order.add(s);
        } else {
            order.addAll(Arrays.asList(getResources().getStringArray(R.array.dock_apps)));
        }
        int n = 0;
        for (String p : order) {
            try { getPackageManager().getPackageInfo(p, 0); n++; } catch (Exception ignored) { }
        }
        dockCount = n;
    }

    // ------------------------------------------------------------------ the cover

    private void showCover() {
        if (cover != null) return;
        try {
            float d = getResources().getDisplayMetrics().density;
            prefetchWallpaper();   // keeps the next photo warm; this frame uses whatever is already decoded
            FrameLayout f = new FrameLayout(this);
            f.setBackgroundColor(Color.BLACK);

            // Whatever is on the launcher's screen right now, borrowed from its process (same app). Falls
            // back to our own decode when the launcher has never run, so a press is never a black flash.
            Bitmap photo = MainActivity.currentWall != null ? MainActivity.currentWall : coverWall;
            if (photo != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(photo);
                f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }

            View scrim = new View(this);   // the launcher's five stops, so the photo keeps the same brightness
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
            f.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            View tint = new View(this);    // the launcher's time-of-day wash
            tint.setBackgroundColor(hour >= 18 || hour < 5 ? 0x26FF7A2E : hour < 10 ? 0x1A7FB8FF : 0x00000000);
            f.addView(tint, new FrameLayout.LayoutParams(-1, -1));

            View glow = new View(this);    // the launcher's ambient colour behind the dock
            GradientDrawable g = new GradientDrawable();
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setGradientCenter(0.5f, 0.78f);
            g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
            int gc = MainActivity.glowNow;
            g.setColors(new int[]{withAlpha(gc, 0.5f), withAlpha(gc, 0.14f), Color.TRANSPARENT});
            glow.setBackground(g);
            f.addView(glow, new FrameLayout.LayoutParams(-1, -1));

            addHeader(f, d);
            addPanel(f, d);
            addDockBand(f, d);
            addLoadingHint(f, d);

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

    /**
     * The launcher's header, at its exact sizes and offsets. The cover used to draw a 64sp clock in the
     * corner with no greeting, date or pills, so pressing Home read as three screens: Amazon's, a
     * near-miss, then the launcher. Matching the chrome makes it one screen settling.
     */
    private void addHeader(FrameLayout f, float d) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        Date now = new Date();
        String family = getString(R.string.family_name);
        String device = getString(R.string.device_name);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView greet = new TextView(this);
        greet.setText((hour < 5 ? "GOOD NIGHT" : hour < 12 ? "GOOD MORNING" : hour < 17 ? "GOOD AFTERNOON" : "GOOD EVENING")
                + (family.isEmpty() ? "" : ",  " + family.toUpperCase(Locale.getDefault())));
        greet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        greet.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        greet.setTextColor(0xB3FFFFFF);
        greet.setLetterSpacing(0.25f);
        greet.setSingleLine(true);
        left.addView(greet);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        TextView clk = new TextView(this);
        clk.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
        clk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        clk.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clk.setTextColor(Color.WHITE);
        clk.setIncludeFontPadding(false);
        clockRow.addView(clk);
        TextView ampm = new TextView(this);
        ampm.setText(is24 ? "" : (hour < 12 ? "AM" : "PM"));
        ampm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ampm.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        ampm.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.setMargins((int) (6 * d), 0, 0, (int) (8 * d));
        clockRow.addView(ampm, ap);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.setMargins(0, (int) (2 * d), 0, (int) (2 * d));
        left.addView(clockRow, rp);

        TextView date = new TextView(this);
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault())
                + (device.isEmpty() ? "" : "   \u00B7   " + device.toUpperCase(Locale.getDefault())));
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setTextColor(0x99FFFFFF);
        date.setLetterSpacing(0.22f);
        date.setSingleLine(true);
        left.addView(date);
        for (TextView t : new TextView[]{greet, clk, ampm, date}) t.setShadowLayer(10 * d, 0, (int) d, 0xB3000000);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (420 * d), -2, Gravity.TOP | Gravity.START);
        lp.setMargins((int) (48 * d), (int) (36 * d), 0, 0);
        f.addView(left, lp);

        // Top-right pills, right-aligned and 30dp tall like the launcher's, so the corner does not shift.
        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        List<TextView> bar = new ArrayList<>();
        if (hasPassthroughInputs()) bar.add(pill("Inputs", R.drawable.ic_input, d));
        boolean ethernet = isEthernet();
        bar.add(pill(netLabel(ethernet), ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi, d));
        bar.add(pill("\u2699  Settings", 0, d));
        for (TextView p : bar) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, (int) (30 * d));
            plp.setMargins((int) (10 * d), 0, 0, 0);
            right.addView(p, plp);
        }
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        hp.setMargins(0, (int) (36 * d), (int) (48 * d), 0);
        f.addView(right, hp);
    }

    private TextView pill(String text, int iconRes, float d) {
        TextView p = new TextView(this);
        p.setText(text);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextColor(0xE6FFFFFF);
        p.setGravity(Gravity.CENTER);
        p.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(15 * d);
        bg.setColor(0x66000000);
        bg.setStroke((int) d, 0x33FFFFFF);
        p.setBackground(bg);
        if (iconRes != 0) {
            android.graphics.drawable.Drawable ic = getDrawable(iconRes);
            ic.setBounds(0, 0, (int) (16 * d), (int) (16 * d));
            p.setCompoundDrawables(ic, null, null, null);
            p.setCompoundDrawablePadding((int) (8 * d));
            p.setCompoundDrawableTintList(ColorStateList.valueOf(0xE6FFFFFF));
        }
        return p;
    }

    private boolean isEthernet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_ETHERNET;
    }

    /** Same label the launcher's pill shows, including the SSID and link speed. */
    private String netLabel(boolean ethernet) {
        if (ethernet) return "Ethernet";
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Offline";
                else if (wm.getConnectionInfo().getLinkSpeed() > 0) name += "  \u00B7  " + wm.getConnectionInfo().getLinkSpeed() + " Mbps";
            }
        } catch (SecurityException ignored) { }
        return name;
    }

    /** The launcher's info panel with its exact geometry, padding and line colours. */
    private void addPanel(FrameLayout f, float d) {
        String json = getSharedPreferences("launcher", MODE_PRIVATE).getString("ha_json", "");
        if (json.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(json);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding((int) (22 * d), (int) (16 * d), (int) (22 * d), (int) (16 * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(22 * d);
            bg.setColor(0x73000000);
            bg.setStroke((int) d, 0x26FFFFFF);
            card.setBackground(bg);

            String cond = j.optString("condition", "");
            TextView big = new TextView(this);
            big.setText(MainActivity.weatherEmoji(cond) + "  " + j.optString("temp", "--") + "\u00B0");
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
            big.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            big.setTextColor(Color.WHITE);
            card.addView(big);

            TextView sub = new TextView(this);
            String feels = j.optString("feels", "");
            sub.setText((cond.toUpperCase(Locale.getDefault()) + (feels.isEmpty() ? "" : "  \u00B7  FEELS " + feels + "\u00B0")).trim());
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            sub.setTextColor(0xCCFFFFFF);
            sub.setLetterSpacing(0.18f);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.setMargins(0, 0, 0, (int) (10 * d));
            card.addView(sub, sp);

            String rain = j.optString("rain", "");
            boolean wet = rain.startsWith("Raining") || rain.contains("on the way");
            SpannableStringBuilder r = new SpannableStringBuilder();
            if (!rain.isEmpty()) coloured(r, (wet ? "\u2614  " : "\uD83C\uDF24\uFE0F  ") + rain, wet ? 0xFF7CC4FF : 0xFF8FE3C8);
            panelLine(card, r, d);

            String solar = j.optString("solar", "");
            SpannableStringBuilder p = new SpannableStringBuilder();
            if (!solar.isEmpty()) {
                int soc = j.optInt("battery", 0);
                coloured(p, "\u2600\uFE0F " + solar + " kW", 0xFFFFC857);
                coloured(p, "     \uD83D\uDD0B " + soc + "%", soc < 20 ? 0xFFFF6B6B : soc < 50 ? 0xFFFFC857 : 0xFF5BE3A1);
                coloured(p, "     \uD83C\uDFE0 " + j.optString("house", "--") + " kW", 0xFF9AB0FF);
            }
            panelLine(card, p, d);

            String bins = j.optString("bins", "");
            SpannableStringBuilder b = new SpannableStringBuilder();
            if (!bins.isEmpty()) coloured(b, "\uD83D\uDDD1\uFE0F  " + bins, bins.startsWith("General") ? 0xFFFF8A80 : bins.startsWith("Recycl") ? 0xFFFFE066 : 0xFFFFFFFF);
            panelLine(card, b, d);

            String next = j.optString("next", "");
            SpannableStringBuilder n = new SpannableStringBuilder();
            if (!next.isEmpty()) coloured(n, "\uD83D\uDCC5  " + next, 0xFFC9B6FF);
            panelLine(card, n, d);

            String inside = j.optString("inside", "");
            String ac = j.optString("ac", "");
            SpannableStringBuilder in = new SpannableStringBuilder();
            if (!inside.isEmpty()) coloured(in, "\uD83C\uDF21\uFE0F  " + inside + "\u00B0 inside" + (ac.isEmpty() ? "" : "     \u2744\uFE0F AC " + ac), 0xFF9FE8FF);
            panelLine(card, in, d);

            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams((int) (360 * d), -2, Gravity.END | Gravity.TOP);
            cp.setMargins(0, (int) (110 * d), (int) (48 * d), 0);
            f.addView(card, cp);
        } catch (Exception ignored) { }
    }

    private void panelLine(LinearLayout card, SpannableStringBuilder sb, float d) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setTextColor(0xE6FFFFFF);
        t.setText(sb);
        t.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(0, (int) (3 * d), 0, 0);
        card.addView(t, p);
    }

    private static SpannableStringBuilder coloured(SpannableStringBuilder sb, String t, int color) {
        int st = sb.length();
        sb.append(t);
        sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * The launcher's dock band at the same size and place, so the bottom of the screen does not jump.
     * Empty: the icons themselves arrive with the launcher a few hundred ms later.
     */
    private void addDockBand(FrameLayout f, float d) {
        int n = dockCount;
        if (n <= 0) { bandHeight = 0; return; }
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setGravity(Gravity.CENTER);
        band.setPadding((int) (20 * d), (int) (22 * d), (int) (20 * d), (int) (22 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(26 * d);
        bg.setColor(0x1FFFFFFF);
        bg.setStroke((int) d, 0x14FFFFFF);
        band.setBackground(bg);
        for (int i = 0; i < n; i++) {
            View box = new MainActivity.AspectFrame(this);   // same 16:9 measurement as a real tile
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : (int) (10 * d), 0, i == n - 1 ? 0 : (int) (10 * d), 0);
            band.addView(box, p);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins((int) (48 * d), 0, (int) (48 * d), (int) (30 * d));
        f.addView(band, lp);
        band.measure(View.MeasureSpec.makeMeasureSpec(
                        getResources().getDisplayMetrics().widthPixels - (int) (96 * d), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        bandHeight = band.getMeasuredHeight();
    }

    /** Only appears if the launcher is slow to arrive: on frame one the cover is an exact copy of Home. */
    private void addLoadingHint(final FrameLayout f, float d) {
        TextView msg = new TextView(this);
        msg.setText("Opening Home\u2026");
        msg.setTextColor(0xCCFFFFFF);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msg.setShadowLayer(10 * d, 0, (int) (2 * d), 0xB3000000);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        mp.setMargins((int) (48 * d), 0, 0, (int) (44 * d) + bandHeight);
        f.addView(msg, mp);
        msg.setAlpha(0f);
        ui.postDelayed(() -> { if (cover == f) msg.animate().alpha(1f).setDuration(400).start(); }, 260);

        View bar = new View(this);   // indeterminate loading bar along the bottom
        bar.setBackgroundColor(0xCCFFFFFF);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams((int) (160 * d), (int) (3 * d), Gravity.BOTTOM | Gravity.START);
        f.addView(bar, bp);
        bar.setAlpha(0f);
        ObjectAnimator sweep = ObjectAnimator.ofFloat(bar, View.TRANSLATION_X, -160 * d, getResources().getDisplayMetrics().widthPixels);
        sweep.setDuration(1400);
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.setInterpolator(new AccelerateDecelerateInterpolator());
        sweep.start();
        ui.postDelayed(() -> { if (cover == f) bar.animate().alpha(1f).setDuration(300).start(); }, 340);
    }

    private boolean hasPassthroughInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return false;
        for (TvInputInfo t : tim.getTvInputList())
            if (t.isPassthroughInput() && !t.isHidden(this) && !t.getId().startsWith("com.amazon.")
                    && !t.getId().toLowerCase(Locale.getDefault()).contains("arcinput")) return true;
        return false;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private void hideCover() {
        ui.removeCallbacksAndMessages(null);
        final View c = cover;
        if (c == null) return;
        cover = null;
        c.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(c); } catch (Exception ignored) { }
        }).start();
    }
}
