package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Frosted glass for a panel: it samples the blurred copy of the photo at the panel's own place on
 * screen, so the blur follows the panel when the page scrolls, then tints it and draws a hairline.
 *
 * RenderEffect would do this on the GPU but it needs API 31, and this build targets 28 because
 * Fire OS 7 reports API 28 - so the softness comes from a pre-blurred bitmap decoded with the
 * wallpaper. Until one exists the panel falls back to a plain 55% black, which is readable on
 * any photo.
 */
final class Frost extends Drawable {
    private final View view;
    private final float corner;
    private final float hairline;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint();
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final int[] where = new int[2];
    private Bitmap blur;

    Frost(View view, float cornerPx, float hairlinePx) {
        this.view = view;
        this.corner = cornerPx;
        this.hairline = hairlinePx;
        tint.setColor(0xE6000000);   // 90% black: the dock sits directly behind this sheet and
                                     // the top bar is hidden while it is open, so nothing reads through
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(hairlinePx);
        edge.setColor(0x1FFFFFFF);   // white, 12%
    }

    void setBlur(Bitmap b) {
        blur = b;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        box.set(b);
        clip.reset();
        clip.addRoundRect(box, corner, corner, Path.Direction.CW);

        int save = c.save();
        c.clipPath(clip);
        if (blur != null) {
            int rootW = view.getRootView().getWidth();
            int rootH = view.getRootView().getHeight();
            if (rootW > 0 && rootH > 0) {
                view.getLocationInWindow(where);
                // The blurred copy is a small bitmap standing in for the whole screen. Put the
                // panel's own top-left over the matching spot in it, then scale it up to screen
                // size - the other way round leaves a thumbnail in the corner and the photo
                // showing through everywhere else.
                float kx = rootW / (float) blur.getWidth();
                float ky = rootH / (float) blur.getHeight();
                int bitmap = c.save();
                c.translate(-where[0], -where[1]);
                c.scale(kx, ky);
                c.drawBitmap(blur, 0, 0, blit);
                // The tint has to come off that transform, or it is drawn scaled and translated
                // along with the bitmap and lands somewhere off the panel entirely.
                c.restoreToCount(bitmap);
            }
        }
        c.drawRect(box, tint);
        c.restoreToCount(save);

        // The hairline is centred on the edge, so inset by half a stroke to keep it inside.
        box.inset(hairline / 2f, hairline / 2f);
        c.drawRoundRect(box, corner, corner, edge);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
