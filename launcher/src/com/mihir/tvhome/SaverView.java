package com.mihir.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Date;
import java.util.Random;

/**
 * Screensaver content: a slow-drifting painting, the big clock, the weather and the house's energy.
 *
 * The home screen rotates the landscape photographs; the saver deliberately shows the other half
 * of the collection - the paintings - so resting the TV is a gallery rather than another photo.
 * A quiet caption names the canvas for a few seconds after each change, then fades away.
 * The type and margins are the home screen's own ({@link Typo}, {@link HomeTheme}), scaled the same
 * way, so the saver reads as that screen dimmed rather than as a second app.
 */
public class SaverView extends FrameLayout {
    private static final String ART_DIR = "art";
    private static final float WALL_ALPHA = 0.72f;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final TextView clock, meridian, date, weather, energyToday, next, credit;
    private final TextView batteryValue, solarValue, houseValue;
    private final ImageView batteryMark, solarMark, houseMark;
    private final LinearLayout block, clockRow, energyRow, batteryItem, solarItem, houseItem;
    private final ImageView wall;
    private final RainView rain;
    private String[] walls = new String[0];
    private String wallDir = ART_DIR;
    private int wallIndex = -1;
    private Runnable tick;
    private final Runnable creditFade;
    private boolean running;
    private Bitmap currentWall;

    public SaverView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        wall = new ImageView(c);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setAlpha(WALL_ALPHA);
        addView(wall, new LayoutParams(-1, -1));
        addView(vignette(c), new LayoutParams(-1, -1));
        rain = new RainView(c);   // over the painting, under the words
        addView(rain, new LayoutParams(-1, -1));

        // The paintings are brighter than the photographs were. A soft floor under the type keeps
        // white legible on a wheatfield or a sunset without hiding the canvas above it.
        View wash = new View(c);
        wash.setBackground(HomeTheme.bottomWash());
        addView(wash, new LayoutParams(-1, dp(300), Gravity.BOTTOM));

        block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        clock = label(Typo.CLOCK, Typo.light(c), Color.WHITE);
        // A clock is never an ellipsis. The block used to wrap to its widest line, which on some
        // minute widths was narrower than "08:26" and truncated the minutes to "08:...".
        clock.setEllipsize(null);
        clock.setLetterSpacing(HomeTheme.CLOCK_TRACKING);
        Typo.numbers(clock);
        meridian = label(26, Typo.medium(c), HomeTheme.TEXT_70);
        meridian.setEllipsize(null);
        meridian.setVisibility(GONE);
        clockRow = new LinearLayout(c);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        // Baseline alignment puts the small meridian on the clock's own baseline; a vertical
        // gravity here would win over that and bottom-align the two boxes instead.
        clockRow.setGravity(Gravity.START);
        clockRow.addView(clock);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.leftMargin = dp(10);
        clockRow.addView(meridian, mp);
        block.addView(clockRow, new LinearLayout.LayoutParams(-1, -2));

        date = label(Typo.BODY, Typo.regular(c), HomeTheme.TEXT_70);
        block.addView(date, top(HomeTheme.DATE_TOP));

        weather = label(Typo.CAPTION, Typo.regular(c), HomeTheme.TEXT_90);
        // The same mark the home screen's weather line carries, so the two lines read as one.
        Drawable th = c.getDrawable(R.drawable.ic_thermo).mutate();
        int thPx = dp(18);
        th.setBounds(0, 0, thPx, thPx);
        weather.setCompoundDrawables(th, null, null, null);
        weather.setCompoundDrawablePadding(thPx / 2);
        weather.setCompoundDrawableTintList(ColorStateList.valueOf(HomeTheme.TEXT_70));
        weather.setVisibility(GONE);   // an empty line must not leave its thermometer mark alone
        block.addView(weather, top(18));

        // The house's three moving numbers, on one quiet line: battery, roof, load. No words: the
        // marks are the labels, and a row of three reads at a glance from across the room.
        energyRow = new LinearLayout(c);
        energyRow.setOrientation(LinearLayout.HORIZONTAL);
        energyRow.setGravity(Gravity.CENTER_VERTICAL);
        batteryValue = energyValue();
        batteryMark = mark(R.drawable.ic_battery, HomeTheme.TEXT_70);
        batteryItem = energyItem(batteryMark, batteryValue);
        solarValue = energyValue();
        solarMark = mark(R.drawable.ic_solar, 0xFFFFD60A);   // the sheet's sun yellow
        solarItem = energyItem(solarMark, solarValue);
        houseValue = energyValue();
        houseMark = mark(R.drawable.ic_house, HomeTheme.TEXT_70);
        houseItem = energyItem(houseMark, houseValue);
        energyRow.addView(batteryItem);
        energyRow.addView(solarItem);
        energyRow.addView(houseItem);
        energyRow.setVisibility(GONE);
        block.addView(energyRow, top(10));

        // The day's solar and the battery's runway, quieter than the live row above it.
        energyToday = label(Typo.CAPTION, Typo.regular(c), HomeTheme.TEXT_70);
        energyToday.setVisibility(GONE);
        block.addView(energyToday, top(4));

        next = label(Typo.CAPTION, Typo.regular(c), HomeTheme.ACCENT);
        next.setVisibility(GONE);
        block.addView(next, top(12));

        // A gallery label, not a HUD: the title and painter fade in when the canvas changes and
        // leave before they can sit on the panel all evening.
        credit = label(14f, Typo.regular(c), HomeTheme.TEXT_70);
        credit.setGravity(Gravity.END);
        credit.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
        credit.setAlpha(0f);
        credit.setVisibility(GONE);
        creditFade = () -> credit.animate().alpha(0f).setDuration(1200).withLayer().start();

        // Full width between the home screen's own margins, not a wrap to the longest line: the
        // clock must keep its own measure however wide the next event happens to be. 60dp at the
        // home screen's 0.8 scale is the same 96px the photographs use.
        int safe = dp(HomeTheme.SAFE * HomeTheme.UI_SCALE);
        LayoutParams bp = new LayoutParams(-1, -2, Gravity.START | Gravity.BOTTOM);
        bp.setMargins(safe, 0, safe, dp(HomeTheme.DOCK_BOTTOM * HomeTheme.UI_SCALE));
        addView(block, bp);

        LayoutParams cp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(safe, dp(HomeTheme.SAFE * HomeTheme.UI_SCALE), safe, 0);
        addView(credit, cp);

        try {
            String[] art = c.getAssets().list(ART_DIR);
            if (art != null && art.length > 0) {
                walls = art;
            } else {
                wallDir = "wallpapers";
                String[] photos = c.getAssets().list(wallDir);
                walls = photos == null ? new String[0] : photos;
            }
        } catch (java.io.IOException ignored) { }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * A vignette, drawn once and stretched: a square radial gradient becomes an ellipse on a 16:9
     * screen, which is how a projected image falls off at its edges. The paintings are brighter
     * than the photographs were, and the corners are where the credit and the frame fight.
     */
    private View vignette(Context c) {
        int n = 384;
        Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new RadialGradient(n / 2f, n / 2f, n * 0.62f,
                new int[]{0x00000000, 0x00000000, 0x66000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(bm).drawRect(0, 0, n, n, p);
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setImageBitmap(bm);
        return v;
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

    private TextView energyValue() {
        TextView t = label(Typo.CAPTION, Typo.regular(getContext()), HomeTheme.TEXT_90);
        t.setEllipsize(null);
        Typo.numbers(t);
        return t;
    }

    private ImageView mark(int res, int tint) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        return v;
    }

    private LinearLayout energyItem(ImageView icon, TextView value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(8);
        row.addView(value, p);
        row.setVisibility(GONE);
        return row;
    }

    private LinearLayout.LayoutParams top(int value) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(value);
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
        rain.setRain(false, 0);
        h.removeCallbacksAndMessages(null);
    }

    private void update() {
        Date now = new Date();
        long sec = now.getTime() / 1000L;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
        clock.setText(Times.clockDigits(sec, is24, zone));
        // The home screen leaves the meridian off deliberately. The saver is the one screen that
        // can be read many hours later, so 9:00 there should not be a guess between morning and night.
        String mer = Times.meridian(sec, is24, zone);
        meridian.setText(mer);
        meridian.setVisibility(mer.isEmpty() ? GONE : VISIBLE);
        date.setText(Times.dateLine(sec, zone));
    }

    /**
     * A small drift around the corner rather than a new random spot: the block stays composed the
     * way the home screen is, and the pixels still move enough that nothing sits fixed all night.
     */
    private void shift() {
        int span = dp(48);
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

    private void loadWall() {
        if (walls.length == 0) return;
        // Another painting, never the one already on screen: with four canvases, a random repeat
        // would be noticed long before the ten minutes are up.
        int index = rnd.nextInt(walls.length);
        if (walls.length > 1 && index == wallIndex) index = (index + 1) % walls.length;
        wallIndex = index;
        final String name = wallDir + "/" + walls[index];
        final String creditText = artCredit(walls[index]);
        new Thread(() -> {
            try (InputStream is = getContext().getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bm = BitmapFactory.decodeStream(is, null, o);
                if (bm == null) return;
                h.post(() -> {
                    if (!running) {
                        bm.recycle();
                        return;
                    }
                    currentWall = bm;
                    wall.animate().cancel();
                    wall.setImageBitmap(bm);
                    wall.setAlpha(0f);
                    wall.animate().alpha(WALL_ALPHA).setDuration(1200).start();
                    drift();
                    h.removeCallbacks(creditFade);
                    credit.animate().cancel();
                    if (creditText.isEmpty()) {
                        credit.setVisibility(GONE);
                    } else {
                        credit.setText(creditText);
                        credit.setVisibility(VISIBLE);
                        credit.setAlpha(0f);
                        credit.setTranslationY(dp(4));
                        credit.animate().alpha(1f).translationY(0).setDuration(700).setStartDelay(300)
                                .withLayer().start();
                        h.postDelayed(creditFade, 12_000);
                    }
                });
            } catch (Exception ignored) { }
        }).start();
    }

    /**
     * A long event shrinks rather than being cut: 18sp down to 14sp, then the ellipsis is the last
     * resort. The line starts at the safe margin, so the screen less two margins is what it has.
     */
    private void fitNext(String text) {
        int avail = getResources().getDisplayMetrics().widthPixels
                - 2 * dp(HomeTheme.SAFE * HomeTheme.UI_SCALE);
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typo.CAPTION);
        float width = next.getPaint().measureText(text);
        if (avail > 0 && width > avail) {
            float sp = Math.max(14f, (float) Math.floor(Typo.CAPTION * avail / width));
            next.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
    }

    /**
     * The same snapshot the home screen reads: weather, inside, battery, solar, house load, the
     * day's generation and the battery's runway. Every value is optional and hides its own marks.
     */
    private void fetchInfo() {
        new Thread(() -> {
            final String body = Net.text(getContext().getString(R.string.ha_json_url),
                    2500, 3500, Net.MAX_JSON_BYTES);
            if (body == null) return;
            try {
                final JSONObject j = new JSONObject(body);
                final JSONObject ages = j.optJSONObject("ages");
                final String line = weatherLine(j);
                final String nextEvent = clean(j.optString("next", ""));
                final String cond = j.optString("condition", "");
                final boolean fx = getContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
                        .getBoolean("weather_fx", true);

                h.post(() -> {
                    if (!running) return;
                    weather.setText(line);
                    boolean wet = fx && TimeOfDay.raining(cond);
                    rain.setRain(wet, TimeOfDay.drops(cond));
                    rain.setStorm(wet && TimeOfDay.stormy(cond));
                    weather.setVisibility(line.isEmpty() ? GONE : VISIBLE);
                    weather.setAlpha(Times.staleWeather(age(ages, "temp")) ? HomeTheme.STALE : 1f);
                    applyEnergy(j, ages);
                    next.setText(nextEvent);
                    next.setVisibility(nextEvent.isEmpty() ? GONE : VISIBLE);
                    if (!nextEvent.isEmpty()) fitNext(nextEvent);
                });
            } catch (Exception ignored) { }
        }).start();
    }

    private String weatherLine(JSONObject j) {
        String t = clean(j.optString("temp", ""));
        String cond = clean(j.optString("condition", ""));
        if (!cond.isEmpty()) cond = Character.toUpperCase(cond.charAt(0)) + cond.substring(1);
        String inside = clean(j.optString("inside", ""));
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
        return w.toString();
    }

    private void applyEnergy(JSONObject j, JSONObject ages) {
        String battery = clean(j.optString("battery", ""));
        boolean hasBattery = !battery.isEmpty();
        batteryItem.setVisibility(hasBattery ? VISIBLE : GONE);
        if (hasBattery) {
            double soc = number(battery, 0);
            // Optional signed watts; until HA sends it the mark stays neutral rather than guessing
            // a direction from the solar figure.
            double watts = number(j.optString("bat_power", "0"), 0);
            boolean posCharge = getContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
                    .getBoolean("bat_positive_charging", Batteries.DEFAULT_POSITIVE_IS_CHARGING);
            int state = Batteries.state(soc, watts, posCharge);
            boolean stale = Times.stale(age(ages, "battery"));
            batteryValue.setText(Math.round(soc) + "%");
            batteryMark.setImageResource(state == Batteries.CHARGING && !stale
                    ? R.drawable.ic_battery_charging : R.drawable.ic_battery);
            batteryMark.setImageTintList(ColorStateList.valueOf(
                    stale ? Batteries.GREY : Batteries.color(state)));
            batteryItem.setAlpha(stale ? HomeTheme.STALE : 1f);
        }

        String solar = clean(j.optString("solar", ""));
        boolean producing = !solar.isEmpty() && number(solar, 0) > 0.05;
        solarItem.setVisibility(producing ? VISIBLE : GONE);
        if (producing) {
            solarValue.setText(Times.kw(solar) + " kW");
            solarItem.setAlpha(Times.stale(age(ages, "solar")) ? HomeTheme.STALE : 1f);
        }

        String house = clean(j.optString("house", ""));
        boolean hasHouse = !house.isEmpty();
        houseItem.setVisibility(hasHouse ? VISIBLE : GONE);
        if (hasHouse) {
            houseValue.setText(Times.kw(house) + " kW");
            houseItem.setAlpha(Times.stale(age(ages, "house")) ? HomeTheme.STALE : 1f);
        }

        boolean any = hasBattery || producing || hasHouse;
        energyRow.setVisibility(any ? VISIBLE : GONE);
        spaceEnergyRow();

        String today = clean(j.optString("energy_today", ""));
        String left = clean(j.optString("battery_left", ""));
        String detail = Cards.solarLine(today.isEmpty() ? "" : Times.kw(today), left);
        energyToday.setText(detail);
        energyToday.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
    }

    /** Gives the first visible energy item no gap and every later one the same quiet gutter. */
    private void spaceEnergyRow() {
        int gap = dp(24);
        boolean first = true;
        for (int i = 0; i < energyRow.getChildCount(); i++) {
            View v = energyRow.getChildAt(i);
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
            boolean visible = v.getVisibility() == VISIBLE;
            p.leftMargin = visible && !first ? gap : 0;
            v.setLayoutParams(p);
            if (visible) first = false;
        }
    }

    /** The four canvases are fixed, so their labels are too; a new file simply has no caption. */
    private static String artCredit(String file) {
        if (file == null) return "";
        switch (file) {
            case "14.jpg": return "Wheatfield with Crows \u00B7 Van Gogh";
            case "16.jpg": return "Nocturne in Blue and Silver \u00B7 Whistler";
            case "18.jpg": return "The Fighting Temeraire \u00B7 Turner";
            case "20.jpg": return "The Hay Wain \u00B7 Constable";
            default: return "";
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.startsWith("unknown") || v.startsWith("unavailable")) return "";
        return v;
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
}
