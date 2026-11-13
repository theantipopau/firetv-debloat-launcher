package com.mihir.tvhome;

import java.util.Random;

/**
 * The soundscape's generators: rain, a fire and brown noise, made from random numbers, so nothing
 * ships as a recording. Pure, so its levels are tested without a TV.
 */
final class Noise {
    static final String[] KINDS = {"Off", "Rain", "Fireplace", "Brown noise"};

    private final int kind;   // index into KINDS
    private final Random rng = new Random();
    private float brown, low, high, crackle, crackleTone, swell = 1f, swellTarget = 1f;
    private int swellLeft;

    Noise(int kind) { this.kind = kind; }

    /** Fills buf with samples in [-1, 1] at 44.1 kHz. */
    void fill(float[] buf) {
        for (int i = 0; i < buf.length; i++) buf[i] = clamp(next());
    }

    private float next() {
        float white = rng.nextFloat() * 2f - 1f;
        brown = (brown + 0.02f * white) / 1.02f;
        switch (kind) {
            case 1: {   // rain: hiss with a slow swell, plus the odd close drop
                low += 0.35f * (white - low);
                high = white - low;
                if (--swellLeft <= 0) {
                    swellTarget = 0.7f + rng.nextFloat() * 0.5f;
                    swellLeft = 22050 + rng.nextInt(44100);
                }
                swell += (swellTarget - swell) * 0.00005f;
                if (rng.nextInt(9000) == 0) crackle = 0.5f + rng.nextFloat() * 0.3f;
                crackle *= 0.985f;
                return (0.22f * low + 0.10f * high) * swell + brown * 1.2f + crackle * white * 0.6f;
            }
            case 2: {   // fireplace: a low roar and sharp crackles
                if (rng.nextInt(2600) == 0) {
                    crackle = 0.6f + rng.nextFloat() * 0.4f;
                    crackleTone = 0.2f + rng.nextFloat() * 0.6f;
                }
                crackle *= 0.93f;
                high += crackleTone * (white - high);
                return brown * 2.6f + crackle * high;
            }
            case 3:     // brown noise: the deep, even one people sleep to
                return brown * 3.2f;
            default:
                return 0f;
        }
    }

    private static float clamp(float v) {
        return v > 1f ? 1f : (v < -1f ? -1f : v);
    }

    /** Root-mean-square of a buffer: the test's measure of "audible but not clipping". */
    static double rms(float[] b) {
        double s = 0;
        for (float v : b) s += v * v;
        return Math.sqrt(s / Math.max(1, b.length));
    }
}
