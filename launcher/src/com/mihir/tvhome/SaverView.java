package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver content: slow-drifting wallpaper, big clock, weather and next event. The block shifts
 * so no pixel stays lit.
 *
 * The type and the margins are the home screen's own ({@link Typo}, {@link HomeTheme}), so the saver
 * reads as that screen dimmed rather than as a second app: Inter Light for the clock, the same 70%
 * white date, the weather in the line colour and only the next event in the accent.
 */
public class SaverView extends FrameLayout {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final TextView clock, date, weather, next;
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
        clock = label(Typo.CLOCK, Typo.light(c), Color.WHITE);
        // A clock is never an ellipsis. The block used to wrap to its widest line, which on some
        // minute widths was narrower than "08:26" and truncated the minutes to "08:...".
        clock.setEllipsize(null);
        clock.setLetterSpacing(HomeTheme.CLOCK_TRACKING);
        Typo.numbers(clock);
        date = label(Typo.BODY, Typo.regular(c), HomeTheme.TEXT_70);
        weather = label(Typo.CAPTION, Typo.regular(c), HomeTheme.TEXT_90);
        // The same mark the home screen's weather line carries, so the two lines read as one.
        Drawable th = c.getDrawable(R.drawable.ic_thermo).mutate();
        int thPx = Math.round(18 * c.getResources().getDisplayMetrics().density);
        th.setBounds(0, 0, thPx, thPx);
        weather.setCompoundDrawables(th, null, null, null);
        weather.setCompoundDrawablePadding(thPx / 2);
        weather.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(HomeTheme.TEXT_70));
        next = label(Typo.CAPTION, Typo.regular(c), HomeTheme.ACCENT);
        block.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        block.addView(date, top(HomeTheme.DATE_TOP));
        block.addView(weather, top(18));
        block.addView(next, top(4));
        weather.setVisibility(GONE);
        next.setVisibility(GONE);
        // Full width between the home screen's safe margins, not a wrap to the longest line: the
        // clock must keep its own measure however wide the next event happens to be.
        LayoutParams bp = new LayoutParams(-1, -2, Gravity.START | Gravity.BOTTOM);
        float d = c.getResources().getDisplayMetrics().density;
        bp.setMargins((int) (HomeTheme.SAFE * d), 0, (int) (HomeTheme.SAFE * d),
                (int) (HomeTheme.DOCK_BOTTOM * d));
        addView(block, bp);
        try { walls = c.getAssets().list("wallpapers"); } catch (java.io.IOException ignored) { }
    }

    private TextView label(float sp, Typeface face, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setShadowLayer(14, 0, 2, 0xCC000000);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = Math.round(dp * getResources().getDisplayMetrics().density);
        return p;
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
                n++;
                if (n > 1 && n % 30 == 0) shift();
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
        clock.setText(Times.clockDigits(now.getTime() / 1000L, is24, java.time.ZoneId.systemDefault()));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
    }

    /**
     * A small drift around the corner rather than a new random spot: the block stays composed the
     * way the home screen is, and the pixels still move enough that nothing sits fixed all night.
     */
    private void shift() {
        float d = getResources().getDisplayMetrics().density;
        int span = Math.round(48 * d);
        block.animate().translationX(rnd.nextInt(span) - span / 2f)
                .translationY(rnd.nextInt(span) - span / 2f)
                .setDuration(4000).start();
    }

    /**
     * The slow zoom, stepped four times a second rather than animated every frame: about half a
     * pixel a step, and a quarter of the redraws. The saver runs all night, so frames are watts.
     */
    private void drift() {
        final long from = android.os.SystemClock.elapsedRealtime();
        final Bitmap mine = currentWall;
        h.post(new Runnable() {
            @Override public void run() {
                if (!running || mine != currentWall) return;
                float t = Math.min(1f, (android.os.SystemClock.elapsedRealtime() - from) / 120_000f);
                wall.setScaleX(1f + 0.12f * t);
                wall.setScaleY(1f + 0.12f * t);
                if (t < 1f) h.postDelayed(this, 250);
            }
        });
    }

    private Bitmap currentWall;
    private boolean firstWall = true;

    private void loadWall() {
        if (walls.length == 0) return;
        // The first photo is the one the home screen was showing, so the saver arrives as that
        // picture dimming down rather than a cut to a different one.
        int index = rnd.nextInt(walls.length);
        if (firstWall) {
            int home = getContext().getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                    .getInt("wallpaper", -1);
            if (home >= 0 && home < walls.length) index = home;
            firstWall = false;
        }
        final String name = "wallpapers/" + walls[index];
        new Thread(() -> {
            try (InputStream is = getContext().getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bm = BitmapFactory.decodeStream(is, null, o);
                h.post(() -> {
                    if (!running) return;
                    currentWall = bm;
                    wall.setAlpha(0f);
                    wall.setImageBitmap(bm);
                    wall.animate().alpha(0.55f).setDuration(1200).start();
                    drift();
                });
            } catch (Exception ignored) { }
        }).start();
    }

    /**
     * A long event shrinks rather than being cut: 18sp down to 14sp, then the ellipsis is the last
     * resort. The line starts at the safe margin, so the screen less two margins is what it has.
     */
    private void fitNext(String text) {
        float d = getResources().getDisplayMetrics().density;
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * Math.round(HomeTheme.SAFE * d);
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.CAPTION);
        float width = next.getPaint().measureText(text);
        if (avail > 0 && width > avail) {
            float sp = Math.max(14f, (float) Math.floor(Typo.CAPTION * avail / width));
            next.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
    }

    /** Same JSON the home panel reads: temp, condition, inside, next. */
    private void fetchInfo() {
        new Thread(() -> {
            final String body = Net.text(getContext().getString(R.string.ha_json_url), 2500, 3500, Net.MAX_JSON_BYTES);
            if (body == null) return;
            try {
                org.json.JSONObject j = new org.json.JSONObject(body);
                String t = j.optString("temp", "").trim();
                String cond = j.optString("condition", "").trim();
                if (!cond.isEmpty()) cond = Character.toUpperCase(cond.charAt(0)) + cond.substring(1);
                String inside = j.optString("inside", "").trim();
                StringBuilder w = new StringBuilder();
                if (!t.isEmpty()) w.append(t).append("\u00B0");
                if (!cond.isEmpty()) {
                    if (w.length() > 0) w.append("  \u00B7  ");
                    w.append(cond);
                }
                if (!inside.isEmpty()) {
                    if (w.length() > 0) w.append("  \u00B7  ");
                    w.append(Times.kw(inside)).append("\u00B0 inside");
                }
                final String line = w.toString();
                final String nextEvent = j.optString("next", "").trim();
                h.post(() -> {
                    if (!running) return;
                    weather.setText(line);
                    weather.setVisibility(line.isEmpty() ? GONE : VISIBLE);
                    next.setText(nextEvent);
                    next.setVisibility(nextEvent.isEmpty() ? GONE : VISIBLE);
                    if (!nextEvent.isEmpty()) fitNext(nextEvent);
                });
            } catch (Exception ignored) { }
        }).start();
    }
}
