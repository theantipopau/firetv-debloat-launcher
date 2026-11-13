package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * Rain over the wallpaper: slanted streaks falling at slightly different speeds. A few dozen lines
 * at 30 frames a second, drawn only while it is raining and the home screen is showing, so a quiet
 * day costs nothing.
 */
final class RainView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.Random rng = new java.util.Random();
    private float[] x = new float[0], y = new float[0], speed = new float[0], len = new float[0];
    private int[] alpha = new int[0];
    private boolean on, storm;
    /** Lightning: the flash's brightness (0-1) and when the next one comes. */
    private float flash;
    private long nextFlash;
    private long last;

    RainView(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2f * c.getResources().getDisplayMetrics().density);
        paint.setColor(0xFFDCE8FF);
        setVisibility(GONE);
    }

    /** Turns rain on with this many streaks, or off. */
    void setStorm(boolean s) {
        if (s && !storm) nextFlash = SystemClock.uptimeMillis() + 4000 + rng.nextInt(8000);
        storm = s;
    }

    void setRain(boolean wet, int count) {
        if (wet == on && (!wet || count == x.length)) return;
        on = wet;
        if (!wet) {
            setVisibility(GONE);
            return;
        }
        x = new float[count];
        y = new float[count];
        speed = new float[count];
        len = new float[count];
        alpha = new int[count];
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < count; i++) {
            x[i] = rng.nextFloat();
            y[i] = rng.nextFloat();
            speed[i] = (0.9f + rng.nextFloat() * 0.9f);            // screens per second
            len[i] = (14f + rng.nextFloat() * 22f) * d;
            alpha[i] = 40 + rng.nextInt(70);
        }
        last = SystemClock.uptimeMillis();
        setVisibility(VISIBLE);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!on) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - last) / 1000f);
        last = now;
        int w = getWidth(), h = getHeight();
        float slant = 0.18f;   // a light wind: horizontal travel per unit of fall
        for (int i = 0; i < x.length; i++) {
            y[i] += speed[i] * dt;
            if (y[i] > 1f) {
                y[i] -= 1.1f;
                x[i] = rng.nextFloat();
            }
            float px = x[i] * (w + h * slant) - h * slant * y[i];
            float py = y[i] * h;
            paint.setAlpha(alpha[i]);
            canvas.drawLine(px, py, px + len[i] * slant, py - len[i], paint);
        }
        if (storm && now >= nextFlash) {
            // A strike is two flickers a beat apart, then 8-25 seconds of dark.
            flash = flash > 0.05f ? 0.75f : 1f;
            nextFlash = flash == 0.75f ? now + 8000 + rng.nextInt(17000) : now + 140;
        }
        if (flash > 0.01f) {
            canvas.drawColor(((int) (flash * 70) << 24) | 0xE8EEFF);
            flash *= (float) Math.pow(0.02, dt / 0.6);   // gone in about 0.6 s
        }
        postInvalidateDelayed(33);   // about 30 fps
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        on = false;
    }
}
