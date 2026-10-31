package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The painted part of an app tile: background, art, the glow behind a bare icon and the hairline
 * rim. Shared because the Home-key cover has to draw the dock at exactly the same size and place;
 * only the home screen adds focus, labels and the sheen on top.
 */
final class AppTile {
    private AppTile() { }

    static FrameLayout card(Context c, MainActivity.App app, float d) {
        FrameLayout card = new MainActivity.AspectFrame(c);
        GradientDrawable bg = new GradientDrawable();
        if (app.isBanner) {
            bg.setColor(0xFF1C1C1E);
        } else {   // no banner art (sideloaded apps such as TiviMate): colour gradient with a soft highlight
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            bg.setColors(new int[]{HomeTheme.darken(app.color, 0.85f),
                    HomeTheme.darken(app.color, 0.4f), HomeTheme.darken(app.color, 0.22f)});
        }
        bg.setCornerRadius(px(d, HomeTheme.TILE_RADIUS));
        card.setBackground(bg);

        ImageView img = new ImageView(c);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(px(d, 30), px(d, 10), px(d, 30), px(d, 26));
            View glowDisc = new View(c);   // light pooling behind the icon
            GradientDrawable gd = new GradientDrawable();
            gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            gd.setGradientRadius(px(d, 90));
            gd.setGradientCenter(0.5f, 0.42f);
            gd.setColors(new int[]{(0x55 << 24) | (0x00FFFFFF & app.color), 0x00000000});
            glowDisc.setBackground(gd);
            card.addView(glowDisc, 0, new FrameLayout.LayoutParams(-1, -1));
            TextView nm = new TextView(c);
            nm.setText(app.label);
            nm.setTextSize(10);
            nm.setTextColor(0xCCFFFFFF);
            nm.setTypeface(Typo.medium(c));
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(px(d, 8), 0, px(d, 8), px(d, 6));
            card.addView(nm, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        GradientDrawable rim = new GradientDrawable();
        rim.setStroke(Math.max(1, Math.round(d)), HomeTheme.RIM);
        rim.setCornerRadius(px(d, HomeTheme.TILE_RADIUS));
        View edge = new View(c);
        edge.setBackground(rim);
        card.addView(edge, new FrameLayout.LayoutParams(-1, -1));

        // Clipping is part of the tile, not of the screen that shows it: an app banner is a square
        // drawable, so without this the radius exists only in the rim and both docks look square.
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(d, HomeTheme.TILE_RADIUS));
            }
        });
        return card;
    }

    /**
     * A dock tile: the app's banner filling a 16:9 plate, or its icon centred when it has no banner
     * (sideloaded apps). No name inside - the dock shows one under the focused tile - but the same
     * radius, rim and clip as the rest of the screen. Shared with the Home-key cover.
     */
    static FrameLayout dock(Context c, MainActivity.App app, float d) {
        FrameLayout card = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        if (app.isBanner) {
            bg.setColor(0xFF1C1C1E);
        } else {
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            bg.setColors(new int[]{HomeTheme.darken(app.color, 0.85f),
                    HomeTheme.darken(app.color, 0.42f), 0xFF141417});
        }
        bg.setCornerRadius(px(d, HomeTheme.DOCK_ICON_RADIUS));
        card.setBackground(bg);

        ImageView img = new ImageView(c);
        if (app.isBanner) {
            img.setImageDrawable(app.art);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setImageDrawable(app.icon != null ? app.icon : app.art);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(px(d, 8), px(d, 6), px(d, 8), px(d, 6));
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        }

        GradientDrawable rim = new GradientDrawable();
        rim.setStroke(Math.max(1, Math.round(d)), HomeTheme.RIM);
        rim.setCornerRadius(px(d, HomeTheme.DOCK_ICON_RADIUS));
        View edge = new View(c);
        edge.setBackground(rim);
        card.addView(edge, new FrameLayout.LayoutParams(-1, -1));

        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(d, HomeTheme.DOCK_ICON_RADIUS));
            }
        });
        return card;
    }

    /**
     * The focused tile's light edge: 1dp on its own rim, laid over the tile. The tile's lift and
     * shadow are the cue; this only keeps a white app plate from melting into a white sky.
     */
    static View focusRim(Context c, float d) {
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(px(d, HomeTheme.DOCK_ICON_RADIUS));
        rim.setStroke(Math.max(1, Math.round(d)), HomeTheme.FOCUS_RIM);
        View v = new View(c);
        v.setBackground(rim);
        return v;
    }

    /** Room the blur needs around the tile, and how far the shadow falls below it, in dp. */
    static final int SHADOW_PAD = 16;
    static final int SHADOW_DROP = 7;
    private static final java.util.Map<String, Bitmap> SHADOWS = new java.util.HashMap<>();

    /**
     * A focus shadow drawn once and reused as a picture. Elevation shadows are recomputed by the
     * GPU every frame a tile scales or leans, and that was the slow tenth of frames while moving
     * along the dock; fading a cached bitmap costs a texture blit. Add it behind the tile with
     * {@link #shadowParams}, at alpha 0, and fade it with focus.
     */
    static ImageView shadow(Context c, float d, int wDp, int hDp, int radiusDp) {
        String key = wDp + "x" + hDp + "r" + radiusDp + "@" + d;
        Bitmap bm = SHADOWS.get(key);
        if (bm == null) {
            int pad = px(d, SHADOW_PAD);
            bm = Bitmap.createBitmap(px(d, wDp) + pad * 2, px(d, hDp) + pad * 2, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x99000000);
            p.setMaskFilter(new BlurMaskFilter(px(d, 9), BlurMaskFilter.Blur.NORMAL));
            float r = px(d, radiusDp);
            new Canvas(bm).drawRoundRect(new RectF(pad, pad, pad + px(d, wDp), pad + px(d, hDp)), r, r, p);
            SHADOWS.put(key, bm);
        }
        ImageView v = new ImageView(c);
        v.setImageBitmap(bm);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setAlpha(0f);
        return v;
    }

    /** Lays a {@link #shadow} out around a tile that fills its parent, dropped below it. */
    static FrameLayout.LayoutParams shadowParams(float d) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -1);
        int pad = px(d, SHADOW_PAD), drop = px(d, SHADOW_DROP);
        p.setMargins(-pad, -pad + drop, -pad, -pad - drop);
        return p;
    }

    /** A soft band of light that crosses a tile once when it takes focus; see {@link #sweep}. */
    static View sheen(Context c) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}));
        v.setAlpha(0f);
        return v;
    }

    static void sweep(View sheen, int width) {
        sheen.animate().cancel();
        sheen.setTranslationX(-width);
        sheen.setAlpha(0.9f);
        sheen.animate().translationX(width).alpha(0f)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .setDuration(520).start();
    }

    private static int px(float d, int dp) {
        return Math.round(dp * d);
    }
}
