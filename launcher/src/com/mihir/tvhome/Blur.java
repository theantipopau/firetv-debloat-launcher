package com.mihir.tvhome;

import android.graphics.Bitmap;

/**
 * A small, heavily blurred copy of the photo, ready for a panel to sample.
 *
 * The blur is done once per wallpaper change on a background thread: a 160x90 copy is 14k pixels,
 * so three box-blur passes cost nothing, while doing it live at screen size would cost a frame.
 */
final class Blur {
    private Blur() { }

    static Bitmap frost(Bitmap src, int w, int h) {
        if (src == null || w <= 0 || h <= 0) return null;
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(src, w, h, true);
        } catch (Throwable t) {
            return null;
        }
        if (small == null) return null;
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        // Three passes of a box blur approximate a Gaussian closely enough to read as glass.
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, 3);
            boxV(tmp, px, w, h, 3);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + clamp(i, w)];
                a += c >>> 24;
                red += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((a / div) << 24) | ((red / div) << 16) | ((g / div) << 8) | (b / div);
                int add = in[row + clamp(x + r + 1, w)];
                int sub = in[row + clamp(x - r, w)];
                a += (add >>> 24) - (sub >>> 24);
                red += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                b += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[clamp(i, h) * w + x];
                a += c >>> 24;
                red += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / div) << 24) | ((red / div) << 16) | ((g / div) << 8) | (b / div);
                int add = in[clamp(y + r + 1, h) * w + x];
                int sub = in[clamp(y - r, h) * w + x];
                a += (add >>> 24) - (sub >>> 24);
                red += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                b += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : v >= max ? max - 1 : v;
    }
}
