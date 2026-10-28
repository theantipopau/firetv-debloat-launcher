package com.mihir.tvhome;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * Pops a small card over whatever is playing (top right). Trigger, e.g. from Home Assistant over ADB:
 *   am broadcast -n com.mihir.tvhome/.AlertReceiver --es title "Doorbell" --es text "Someone is at the front door"
 *     --es kind doorbell --es brand google --es image http://host/snapshot.jpg --ei seconds 8
 * kind: doorbell | motion | warn | info.  brand: google | tuya | (none).  Needs "Display over other apps":
 *   appops set com.mihir.tvhome SYSTEM_ALERT_WINDOW allow
 *
 * Only the TV's shell (ADB, which is how Home Assistant sends this) and system apps can trigger a card: the
 * receiver declares android.permission.DUMP in AndroidManifest.xml, which a normal installed app does not hold,
 * so apps on the TV cannot paint fake alerts. If you have a sender that is neither the shell nor a system app,
 * remove that attribute from the manifest (and accept that any app can then spoof cards).
 * Payloads are also rate-limited, and snapshot downloads and decoded image sizes are capped, so a rogue or a
 * broken integration cannot exhaust memory or flood the screen with cards.
 */
public class AlertReceiver extends BroadcastReceiver {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static View current;
    private static WindowManager currentWm;

    /** Snapshots decode at the old fixed inSampleSize=2 and are only sampled further above this many pixels;
     *  the download itself is capped in Net. */
    private static final long MAX_IMAGE_PIXELS = 4_000_000L;
    private static final int MAX_URL = 2048, MAX_TITLE = 120, MAX_TEXT = 300;
    /** Alerts cost a thread and a bitmap: a burst of 6 is fine, after that one every 2 seconds. */
    private static final double BURST = 6;
    private static final long REFILL_MS = 2000;
    private static final Object GATE = new Object();
    private static double tokens = BURST;
    private static long tokenStamp;

    @Override
    public void onReceive(Context ctx, Intent in) {
        final Context app = ctx.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) { Log.w("TvHome", "alert: no overlay permission"); return; }
        if (!allowed()) { Log.w("TvHome", "alert: rate limited, dropping"); return; }
        final String title = cap(nz(in.getStringExtra("title"), "Home"), MAX_TITLE);
        final String text = cap(nz(in.getStringExtra("text"), ""), MAX_TEXT);
        final String kind = nz(in.getStringExtra("kind"), "info");
        final String brand = safeBrand(nz(in.getStringExtra("brand"), ""));
        final String image = cap(nz(in.getStringExtra("image"), ""), MAX_URL);
        final int secs = Math.max(3, Math.min(30, in.getIntExtra("seconds", 8)));
        final PendingResult pr = goAsync();
        new Thread(() -> {
            Bitmap snap = null, logo = null;
            try {
                snap = image.isEmpty() ? null : fetch(image);
                logo = brand.isEmpty() ? null : asset(app, "brands/" + brand + ".png");
            } catch (Throwable t) {
                Log.w("TvHome", "alert payload dropped: " + t);   // never let a bad image kill the launcher process
            }
            final Bitmap fSnap = snap, fLogo = logo;
            try {
                UI.post(() -> {
                    try { show(app, title, text, kind, brand, fLogo, fSnap, secs); }
                    catch (Throwable t) { Log.w("TvHome", "alert show failed: " + t); }
                    finally { pr.finish(); }
                });
            } catch (Throwable t) { pr.finish(); }
        }).start();
    }

    private static String nz(String s, String d) { return s == null || s.isEmpty() ? d : s; }

    private static String cap(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    /** Brand is only ever turned into assets/brands/<brand>.png, so keep it to plain filename characters. */
    private static String safeBrand(String b) { return b.length() <= 32 && b.matches("[A-Za-z0-9._-]+") ? b : ""; }

    /** Token bucket. Normal use (a few alerts a minute) never notices; a flood gets dropped instead of drawn. */
    private static boolean allowed() {
        synchronized (GATE) {
            long now = SystemClock.elapsedRealtime();
            if (tokenStamp == 0) tokenStamp = now;
            tokens = Math.min(BURST, tokens + (now - tokenStamp) / (double) REFILL_MS);
            tokenStamp = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }

    /** http(s) only, capped download and bounded decode (see Net); null means "card without a snapshot". */
    private static Bitmap fetch(String url) {
        Bitmap b = Net.image(url, 2500, 3500, 2, MAX_IMAGE_PIXELS);
        if (b == null) Log.w("TvHome", "alert: snapshot unusable, showing the card without it");
        return b;
    }

    private static Bitmap asset(Context c, String name) {
        try (InputStream is = c.getAssets().open(name)) { return BitmapFactory.decodeStream(is); }
        catch (Exception e) { return null; }
    }

    private static int accent(String kind) {
        switch (kind) {
            case "doorbell": return 0xFFFF453A;
            case "motion": return 0xFFFFB020;
            case "warn": return 0xFFFF9F0A;
            default: return 0xFF4DA3FF;
        }
    }

    private static String glyph(String kind) {
        switch (kind) {
            case "doorbell": return "🔔";
            case "motion": return "🚶";
            case "warn": return "⚠";
            default: return "ℹ";
        }
    }

    static void show(Context c, String title, String text, String kind, String brand, Bitmap logo, Bitmap snap, int secs) {
        dismiss();
        final float d = c.getResources().getDisplayMetrics().density;
        final int acc = accent(kind);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * d);
        bg.setColor(0xEB16161A);
        bg.setStroke((int) d, 0x33FFFFFF);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(20 * d);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));

        FrameLayout badge = new FrameLayout(c);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(logo != null ? 0xFFFFFFFF : brand.equals("tuya") ? 0xFFFF5A1F : (acc & 0x00FFFFFF) | 0x33000000);
        badge.setBackground(bb);
        if (logo != null) {
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(logo);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = (int) (8 * d);
            iv.setPadding(p, p, p, p);
            badge.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView g = new TextView(c);
            g.setText(brand.equals("tuya") ? "t" : glyph(kind));
            g.setTextColor(Color.WHITE);
            g.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            g.setTextSize(TypedValue.COMPLEX_UNIT_SP, brand.equals("tuya") ? 22 : 17);
            g.setGravity(Gravity.CENTER);
            badge.addView(g, new FrameLayout.LayoutParams(-1, -1));
        }
        row.addView(badge, new LinearLayout.LayoutParams((int) (44 * d), (int) (44 * d)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText((logo != null || brand.equals("tuya") ? glyph(kind) + "  " : "") + title);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setSingleLine(true);
        col.addView(t);
        if (!text.isEmpty()) {
            TextView s = new TextView(c);
            s.setText(text);
            s.setTextColor(0xCCFFFFFF);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            s.setMaxLines(2);
            s.setPadding(0, (int) (2 * d), 0, 0);
            col.addView(s);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.leftMargin = (int) (12 * d);
        row.addView(col, cp);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        if (snap != null) {
            ImageView sv = new ImageView(c);
            sv.setImageBitmap(snap);
            sv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(sv, new LinearLayout.LayoutParams(-1, (int) (150 * d)));
        }

        View bar = new View(c);
        bar.setBackgroundColor(acc);
        bar.setPivotX(0f);
        card.addView(bar, new LinearLayout.LayoutParams(-1, (int) (3 * d)));

        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams((int) (380 * d), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = (int) (32 * d);
        lp.y = (int) (28 * d);
        try { wm.addView(card, lp); } catch (Exception e) { Log.w("TvHome", "alert add failed: " + e); return; }
        current = card;
        currentWm = wm;

        card.setAlpha(0f);
        card.setTranslationX(120 * d);
        card.animate().alpha(1f).translationX(0).setDuration(380).setInterpolator(new DecelerateInterpolator(2f)).start();
        ValueAnimator va = ValueAnimator.ofFloat(1f, 0f);
        va.setDuration(secs * 1000L);
        va.setInterpolator(new android.view.animation.LinearInterpolator());
        va.addUpdateListener(a -> bar.setScaleX((float) a.getAnimatedValue()));
        va.start();
        UI.postDelayed(() -> {
            if (current == card) card.animate().alpha(0f).translationX(60 * d).setDuration(320).withEndAction(AlertReceiver::dismiss).start();
        }, secs * 1000L);
    }

    static void dismiss() {
        if (current != null && currentWm != null) {
            try { currentWm.removeView(current); } catch (Exception ignored) { }
        }
        current = null;
    }
}
