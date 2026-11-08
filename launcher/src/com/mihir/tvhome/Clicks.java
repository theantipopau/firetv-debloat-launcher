package com.mihir.tvhome;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * The quiet tick tvOS makes as focus moves, synthesised once rather than shipped as a file: a
 * dozen milliseconds of a decaying tone, a touch lower going left or up than right or down, and a
 * softer, deeper one for OK. Played as UI sound, so the TV's own volume governs it.
 */
final class Clicks {
    private static final int RATE = 44100;
    private static AudioTrack back, forward, select;

    private Clicks() { }

    /** -1 for left/up, +1 for right/down. Never throws: a TV without audio simply stays quiet. */
    static void move(int dir) {
        if (back == null) {
            back = tone(1700, 12, 0.16f);
            forward = tone(2100, 12, 0.16f);
        }
        play(dir < 0 ? back : forward);
    }

    static void select() {
        if (select == null) select = tone(1100, 20, 0.2f);
        play(select);
    }

    static void release() {
        for (AudioTrack t : new AudioTrack[]{back, forward, select}) if (t != null) t.release();
        back = forward = select = null;
    }

    private static void play(AudioTrack t) {
        if (t == null) return;
        try {
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (RuntimeException ignored) { }
    }

    private static AudioTrack tone(int hz, int ms, float amp) {
        try {
            int n = RATE * ms / 1000;
            short[] pcm = new short[n];
            double tau = n / 5.0;   // gone well before the end: no click when the buffer stops
            for (int i = 0; i < n; i++) {
                double env = Math.exp(-i / tau) * Math.min(1, i / 22.0);   // 0.5 ms attack
                pcm[i] = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * env * amp * Short.MAX_VALUE);
            }
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build();
            t.write(pcm, 0, n);
            return t;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
