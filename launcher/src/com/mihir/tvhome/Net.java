package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The launcher's only way out to the network: http(s) only, every fetch bounded by a timeout and a byte cap, and
 * images decoded into a bounded number of pixels. A hostile or broken server (or a hostile URL inside the Home
 * Assistant JSON) can then only cost a dropped card or a missing tile, not the TV's memory or a wedged thread.
 */
final class Net {
    /** The tvhome JSON files are a couple of KB; anything much bigger is not ours. */
    static final int MAX_JSON_BYTES = 512 * 1024;
    /** Artwork and snapshots may download at most this much before the connection is dropped. */
    static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    /** Panel artwork (Plex posters, stills) is decoded exactly as-is up to this many pixels. */
    static final long MAX_PANEL_PIXELS = 6_000_000L;

    private Net() { }

    /** Fetch a small text body (JSON). Null if it fails, is bigger than cap, or is not http(s). */
    static String text(String url, int connectMs, int readMs, int cap) {
        byte[] b = bytes(url, connectMs, readMs, cap);
        if (b == null) return null;
        try { return new String(b, "UTF-8"); }
        catch (Exception e) { return null; }
    }

    static byte[] bytes(String url, int connectMs, int readMs, int cap) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            String scheme = u.getProtocol();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            try (InputStream is = c.getInputStream()) { return read(is, cap); }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Bounded decode: an image up to maxPixels comes out exactly like a plain decode at startSample (so real
     *  artwork is pixel-identical to before), anything bigger is sampled down instead of being allocated whole.
     *  Null if it is not an image, is too big, or the server fails. */
    static Bitmap image(String url, int connectMs, int readMs, int startSample, long maxPixels) {
        byte[] data = bytes(url, connectMs, readMs, MAX_IMAGE_BYTES);
        if (data == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = Math.max(1, startSample);
            while (sample < 32 && (long) o.outWidth * o.outHeight / ((long) sample * sample) > maxPixels) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] read(InputStream is, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int total = 0, n;
        while ((n = is.read(b)) > 0) {
            total += n;
            if (total > cap) return null;
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}
