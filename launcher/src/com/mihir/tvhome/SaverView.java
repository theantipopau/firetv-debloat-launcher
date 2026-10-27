package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** Screensaver content: slow-drifting wallpaper, big clock, weather and next event. The block shifts so no pixel stays lit. */
public class SaverView extends FrameLayout {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final TextView clock, date, info;
    private final LinearLayout block;
    private final ImageView wall;
    private String[] walls = new String[0];
    private Runnable tick;
    private boolean running;

    public SaverView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        wall = new ImageView(c);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setAlpha(0.55f);
        addView(wall, new LayoutParams(-1, -1));

        block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        clock = label(96, "sans-serif-thin", Color.WHITE);
        date = label(20, "sans-serif-medium", 0xCCFFFFFF);
        info = label(18, "sans-serif", 0xFFFFD27A);
        block.addView(clock);
        block.addView(date);
        block.addView(info);
        LayoutParams bp = new LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM);
        float d = c.getResources().getDisplayMetrics().density;
        bp.setMargins((int) (64 * d), 0, 0, (int) (64 * d));
        addView(block, bp);
        try { walls = c.getAssets().list("wallpapers"); } catch (java.io.IOException ignored) { }
    }

    private TextView label(float sp, String family, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(14, 0, 2, 0xCC000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    public void start() {
        if (running) return;
        running = true;
        loadWall();
        fetchInfo();
        tick = new Runnable() {
            int n;
            @Override public void run() {
                if (!running) return;
                update();
                if (n++ % 30 == 0) shift();
                if (n % 600 == 0) loadWall();
                if (n % 300 == 0) fetchInfo();
                h.postDelayed(this, 1000);
            }
        };
        h.post(tick);
    }

    public void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
    }

    private void update() {
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
    }

    private void shift() {
        float d = getResources().getDisplayMetrics().density;
        block.animate().translationX(rnd.nextInt((int) (400 * d))).translationY(-rnd.nextInt((int) (300 * d)))
                .setDuration(3000).start();
    }

    private void drift() {
        wall.setScaleX(1f);
        wall.setScaleY(1f);
        wall.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120_000).setInterpolator(new LinearInterpolator()).start();
    }

    private void loadWall() {
        if (walls.length == 0) return;
        final String name = "wallpapers/" + walls[rnd.nextInt(walls.length)];
        new Thread(() -> {
            try (InputStream is = getContext().getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bm = BitmapFactory.decodeStream(is, null, o);
                h.post(() -> { if (running) { wall.setImageBitmap(bm); drift(); } });
            } catch (Exception ignored) { }
        }).start();
    }

    /** Same JSON the home panel reads: temp, condition, next. */
    private void fetchInfo() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(getContext().getString(R.string.ha_json_url)).openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(3500);
                StringBuilder sb = new StringBuilder();
                try (InputStream is = c.getInputStream()) {
                    byte[] b = new byte[4096];
                    for (int n; (n = is.read(b)) > 0; ) sb.append(new String(b, 0, n, "UTF-8"));
                }
                org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                String s = j.optString("temp", "--") + "°" + (j.has("condition") ? "  ·  " + j.optString("condition") : "");
                String next = j.optString("next", "");
                final String line = next.isEmpty() ? s : s + "\n" + next;
                h.post(() -> info.setText(line));
            } catch (Exception ignored) { }
        }).start();
    }
}
