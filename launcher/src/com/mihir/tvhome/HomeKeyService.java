package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.view.KeyEvent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Fire OS will not let a third-party launcher own Home, so this catches the Home key and opens ours. */
public class HomeKeyService extends AccessibilityService {
    static volatile boolean running;
    private static HomeKeyService instance;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View cover;

    @Override protected void onServiceConnected() { running = true; instance = this; Log.w("TvHome", "HomeKeyService connected"); }
    @Override public boolean onUnbind(Intent intent) { running = false; instance = null; hideCover(); return super.onUnbind(intent); }

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

    private void showCover() {
        if (cover != null) return;
        try {
            float d = getResources().getDisplayMetrics().density;
            FrameLayout f = new FrameLayout(this);
            f.setBackgroundColor(Color.BLACK);
            String[] w = getAssets().list("wallpapers");
            if (w != null && w.length > 0) {
                try (InputStream is = getAssets().open("wallpapers/" + w[getSharedPreferences("MainActivity", MODE_PRIVATE).getInt("wallpaper", 0) % w.length])) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    ImageView iv = new ImageView(this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageBitmap(BitmapFactory.decodeStream(is, null, o));
                    f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                } catch (Exception ignored) { }
            }
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x40000000, 0x8C000000, 0xE6000000}));
            f.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            TextView clk = new TextView(this);
            clk.setText(new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", Locale.getDefault()).format(new Date()));
            clk.setTextColor(Color.WHITE);
            clk.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            clk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
            clk.setShadowLayer(14, 0, 2, 0xB3000000);
            f.addView(clk, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
            ((FrameLayout.LayoutParams) clk.getLayoutParams()).setMargins((int) (48 * d), (int) (46 * d), 0, 0);
            TextView msg = new TextView(this);
            msg.setText("Opening Home…");
            msg.setTextColor(0xCCFFFFFF);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            msg.setShadowLayer(10, 0, 2, 0xB3000000);
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            mp.setMargins((int) (48 * d), 0, 0, (int) (44 * d));
            f.addView(msg, mp);
            msg.setAlpha(0.4f);
            msg.animate().alpha(1f).setDuration(900).start();
            addInfo(f, d);
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

    /** Greeting, weather and house numbers from the last panel refresh, plus a loading bar, so the wait is not blank. */
    private void addInfo(FrameLayout f, float d) {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String name = getString(R.string.family_name);
        TextView greet = new TextView(this);
        greet.setText((h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING")
                + (name.isEmpty() ? "" : ",  " + name.toUpperCase(Locale.getDefault())));
        greet.setTextColor(0xCCFFFFFF);
        greet.setLetterSpacing(0.22f);
        greet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        greet.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        gp.setMargins((int) (48 * d), (int) (28 * d), 0, 0);
        f.addView(greet, gp);

        String json = getSharedPreferences("launcher", MODE_PRIVATE).getString("ha_json", "");
        if (!json.isEmpty()) try {
            org.json.JSONObject j = new org.json.JSONObject(json);
            android.widget.LinearLayout card = new android.widget.LinearLayout(this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setPadding((int) (24 * d), (int) (18 * d), (int) (24 * d), (int) (18 * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(22 * d);
            bg.setColor(0x66101216);
            bg.setStroke((int) d, 0x26FFFFFF);
            card.setBackground(bg);
            TextView big = new TextView(this);
            big.setText(MainActivity.weatherEmoji(j.optString("condition", "")) + "  " + j.optString("temp", "--") + "\u00B0");
            big.setTextColor(Color.WHITE);
            big.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
            card.addView(big);
            StringBuilder sb = new StringBuilder();
            sb.append(j.optString("condition", "").toUpperCase(Locale.getDefault()));
            if (j.has("feels")) sb.append("  \u00B7  FEELS ").append(j.optString("feels")).append("\u00B0");
            card.addView(line(sb.toString(), 0xCCFFFFFF, 11, d));
            String solar = j.optString("solar", "");
            if (!solar.isEmpty())
                card.addView(line("\u2600\uFE0F " + solar + " kW     \uD83D\uDD0B " + j.optInt("battery", 0) + "%     \uD83C\uDFE0 " + j.optString("house", "--") + " kW", 0xFF9FE8C8, 13, d));
            if (!j.optString("bins", "").isEmpty()) card.addView(line("\uD83D\uDDD1\uFE0F " + j.optString("bins"), 0xFFFF9E9E, 13, d));
            if (!j.optString("inside", "").isEmpty())
                card.addView(line("\uD83C\uDF21\uFE0F " + j.optString("inside") + "\u00B0 inside   \u2744\uFE0F AC " + j.optString("ac", "off"), 0xFF9FD8FF, 13, d));
            if (!j.optString("next", "").isEmpty()) card.addView(line("\uD83D\uDCC5 " + j.optString("next"), 0xFFFFD27A, 13, d));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams((int) (360 * d), -2, Gravity.TOP | Gravity.END);
            cp.setMargins(0, (int) (90 * d), (int) (48 * d), 0);
            f.addView(card, cp);
        } catch (Exception ignored) { }

        View bar = new View(this);   // indeterminate loading bar along the bottom
        bar.setBackgroundColor(0xCCFFFFFF);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams((int) (160 * d), (int) (3 * d), Gravity.BOTTOM | Gravity.START);
        f.addView(bar, bp);
        android.animation.ObjectAnimator sweep = android.animation.ObjectAnimator.ofFloat(bar, View.TRANSLATION_X, -160 * d, getResources().getDisplayMetrics().widthPixels);
        sweep.setDuration(1400);
        sweep.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        sweep.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        sweep.start();
    }

    private TextView line(String text, int color, float sp, float d) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, (int) (6 * d), 0, 0);
        return t;
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
