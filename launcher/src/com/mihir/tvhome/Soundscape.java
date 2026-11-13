package com.mihir.tvhome;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/**
 * Plays one {@link Noise} on its own thread at the TV's media volume. It keeps playing over the
 * home screen, the screensaver and the dark screen, stops when an app is opened from Home, and
 * stops by itself after three hours so a forgotten one never runs all night and day.
 */
final class Soundscape {
    private Soundscape() { }

    private static final int RATE = 44100;
    private static final long MAX_MS = 3 * 60 * 60 * 1000L;
    private static volatile Thread player;
    private static volatile int playing;   // index into Noise.KINDS; 0 is off

    static int current() { return playing; }

    static synchronized void play(int kind) {
        stop();
        if (kind <= 0 || kind >= Noise.KINDS.length) return;
        playing = kind;
        final Noise noise = new Noise(kind);
        Thread t = new Thread(() -> run(noise), "soundscape");
        player = t;
        t.start();
    }

    static synchronized void stop() {
        Thread t = player;
        player = null;
        playing = 0;
        if (t != null) t.interrupt();
    }

    private static void run(Noise noise) {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, RATE))   // half a second
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException e) {
            return;
        }
        float[] f = new float[2048];
        short[] s = new short[f.length];
        long start = SystemClock.elapsedRealtime();
        float fade = 0f;   // two seconds up from silence, so it never starts with a burst
        try {
            track.play();
            while (player == Thread.currentThread() && !Thread.interrupted()) {
                if (SystemClock.elapsedRealtime() - start > MAX_MS) break;
                noise.fill(f);
                for (int i = 0; i < f.length; i++) {
                    if (fade < 1f) fade += 1f / (2 * RATE);
                    s[i] = (short) (f[i] * fade * 0.6f * Short.MAX_VALUE);
                }
                track.write(s, 0, s.length);
            }
        } catch (RuntimeException ignored) {
        } finally {
            try { track.stop(); } catch (RuntimeException ignored) { }
            track.release();
            if (player == Thread.currentThread()) { player = null; playing = 0; }
        }
    }
}
