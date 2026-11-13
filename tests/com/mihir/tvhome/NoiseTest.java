package com.mihir.tvhome;

/** Every soundscape is audible, never clips for long, and "Off" is silence. */
public final class NoiseTest {
    public static void main(String[] args) {
        int checks = 0;
        for (int k = 0; k < Noise.KINDS.length; k++) {
            Noise n = new Noise(k);
            float[] buf = new float[44100 * 5];
            n.fill(buf);
            double rms = Noise.rms(buf);
            int clipped = 0;
            for (float v : buf) if (Math.abs(v) >= 1f) clipped++;
            if (k == 0) {
                if (rms != 0) throw new AssertionError("Off must be silent, rms " + rms);
            } else {
                if (rms < 0.02) throw new AssertionError(Noise.KINDS[k] + " too quiet: rms " + rms);
                if (rms > 0.5) throw new AssertionError(Noise.KINDS[k] + " too loud: rms " + rms);
                if (clipped > buf.length / 1000) throw new AssertionError(Noise.KINDS[k] + " clips " + clipped + " samples");
            }
            checks++;
            System.out.printf("  %-12s rms %.3f clipped %d%n", Noise.KINDS[k], rms, clipped);
        }
        System.out.println("NoiseTest: " + checks + " checks passed");
    }
}
