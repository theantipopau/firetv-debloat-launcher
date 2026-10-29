package com.mihir.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
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
        return card;
    }

    private static int px(float d, int dp) {
        return Math.round(dp * d);
    }
}
