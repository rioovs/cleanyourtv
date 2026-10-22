package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One live camera panel on the home screen.
 *
 * The NVR streams H.265, which the RTSP client in Media3 cannot decode, so the panel pulls JPEG
 * snapshots over HTTP instead and shows them one after the other. That is a few hundred kilobytes
 * per frame instead of a video stream, which suits a 1 GB TV, and it needs no audio decoder at all.
 *
 * The download runs on a small thread pool; the bitmap is handed to the view on the main thread.
 *
 * Where the NVR is and who may watch it is not in this file: it comes from the launcher's config
 * overlay (config.json in the app's external files directory), so no password ends up in the APK or
 * in the repository. Without those settings there is no camera on the screen.
 */
final class CctvPanel {
    private static final String TAG = "CctvPanel";

    /** How often a new frame is fetched. Dahua serves the snapshot on request; 5 s keeps the NVR happy. */
    private static final long INTERVAL_MS = 5000;
    private static final int TIMEOUT_MS = 6000;
    /**
     * The panels are not in step with each other: the second one waits half an interval, so the NVR
     * never gets two snapshot requests at the same moment.
     */
    private final long offsetMs;

    private final Context context;
    private final ImageView surface;
    private final int channel;
    private final String host;
    private final String user;
    private final String pass;
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * One thread for both panels: the NVR does not like two snapshot requests at the same time.
     * The two panels share it, so a request of one camera never overlaps the other's.
     */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private boolean running;
    private boolean fetching;
    private Bitmap last;
    /**
     * The digest challenge of the last 401. The realm and the nonce are the same for every channel,
     * so both panels share them: one challenge for the pair, and one request per frame after that.
     * The response itself is computed per URL, because the digest covers the path.
     */
    private static String cachedRealm, cachedNonce, cachedOpaque;

    /**
     * @param host the NVR's address, {@code user} and {@code pass} its login. Empty values mean there
     *             is no camera to show.
     */
    CctvPanel(Context context, ImageView surface, int channel, long offsetMs,
               String host, String user, String pass) {
        this.context = context.getApplicationContext();
        this.surface = surface;
        this.channel = channel;
        this.offsetMs = offsetMs;
        this.host = host;
        this.user = user;
        this.pass = pass;
    }

    /** Start refreshing the panel. Safe to call more than once. */
    void start() {
        if (running) return;
        running = true;
        schedule(offsetMs);
    }

    /**
     * True for a host on the local network: a private IPv4 address, an IPv6 unique-local address or
     * localhost. The camera server is a device on the LAN, and the app talks to nothing else, so this
     * keeps the cleartext permission (see the manifest) from ever reaching the internet.
     */
    static boolean isLocalHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(java.util.Locale.US);
        if (h.equals("localhost") || h.equals("::1")) return true;
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) return false;
        }
        if (octets[0] == 10) return true;                                    // 10.0.0.0/8
        if (octets[0] == 192 && octets[1] == 168) return true;              // 192.168.0.0/16
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) return true;   // 172.16.0.0/12
        if (octets[0] == 169 && octets[1] == 254) return true;              // 169.254.0.0/16
        return false;
    }

    /** Stop refreshing and release the bitmaps. */
    void release() {
        running = false;
        main.removeCallbacksAndMessages(null);
        last = null;
    }

    /** Called when the activity goes away for good. */
    void destroy() {
        release();
    }

    private void schedule(long delay) {
        if (!running) return;
        main.postDelayed(this::fetch, delay);
    }

    private void fetch() {
        if (!running || fetching) return;
        fetching = true;
        POOL.execute(() -> {
            Bitmap frame = null;
            try {
                frame = download();
            } catch (Exception e) {
                Log.w(TAG, "ch" + channel + " snapshot failed: " + e);
            }
            final Bitmap result = frame;
            main.post(() -> {
                fetching = false;
                if (!running) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) {
                    Bitmap old = last;
                    last = result;
                    surface.setImageBitmap(result);
                    if (old != null && old != result) old.recycle();
                }
                schedule(INTERVAL_MS);
            });
        });
    }

    private Bitmap download() throws Exception {
        if (!isLocalHost(host)) throw new IllegalStateException("not a local camera address");
        URL url = new URL("http://" + host + "/cgi-bin/snapshot.cgi?channel=" + channel + "&subtype=0");
        byte[] jpeg = fetch(url);
        if (jpeg == null) throw new IllegalStateException("no image");
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) throw new IllegalStateException("not a JPEG");
        return bmp;
    }

    /**
     * The NVR wants HTTP digest auth (it answers 401 with a challenge). Basic auth is not enough, so the
     * challenge is answered by hand: one request without credentials, one with the digest response.
     */
    private byte[] fetch(URL url) throws Exception {
        // The challenge is reused when it is still there: one request per frame instead of two.
        if (cachedNonce != null) {
            byte[] jpeg = request(url, digest(url.toString()));
            if (jpeg != null) return jpeg;
            cachedRealm = cachedNonce = cachedOpaque = null;   // the nonce went stale, ask for a new one
        }

        String challenge;
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Connection", "keep-alive");
        try {
            int code = conn.getResponseCode();
            if (code == 200) return readAll(conn.getInputStream());
            if (code != 401) throw new IllegalStateException("HTTP " + code);
            challenge = conn.getHeaderField("WWW-Authenticate");
            if (challenge == null) throw new IllegalStateException("HTTP 401 without a challenge");
        } finally {
            conn.disconnect();
        }

        cachedRealm = field(challenge, "realm");
        cachedNonce = field(challenge, "nonce");
        cachedOpaque = field(challenge, "opaque");
        byte[] jpeg = request(url, digest(url.toString()));
        if (jpeg == null) throw new IllegalStateException("HTTP 401 with a bad digest");
        return jpeg;
    }

    /** One request with the given Authorization header; null when the NVR says no. */
    private byte[] request(URL url, String auth) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("Authorization", auth);
        try {
            if (conn.getResponseCode() != 200) return null;
            return readAll(conn.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            conn.disconnect();
        }
    }

    /** Builds the {@code Authorization} header for the current challenge (RFC 2617, qop=auth). */
    private String digest(String uri) {
        String ha1 = md5(user + ":" + cachedRealm + ":" + pass);
        String ha2 = md5("GET:" + uri);
        String nc = "00000001";
        String cnonce = "0a4f113b";
        String response = md5(ha1 + ":" + cachedNonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        StringBuilder head = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(cachedRealm)
                .append("\", nonce=\"").append(cachedNonce)
                .append("\", uri=\"").append(uri).append('"')
                .append(", qop=auth, nc=").append(nc)
                .append(", cnonce=\"").append(cnonce).append('"')
                .append(", response=\"").append(response).append('"');
        if (cachedOpaque != null) head.append(", opaque=\"").append(cachedOpaque).append('"');
        return head.toString();
    }

    /** The value of one quoted field in a challenge, or null. */
    private static String field(String challenge, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "=\"([^\"]*)\"").matcher(challenge);
        return m.find() ? m.group(1) : null;
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 is missing", e);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 * 1024 * 1024) break;   // a snapshot is far smaller than this
            }
            return out.toByteArray();
        }
    }
}
