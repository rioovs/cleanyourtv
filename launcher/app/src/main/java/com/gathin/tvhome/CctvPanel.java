package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * One live camera panel on the home screen.
 *
 * The NVR serves each camera as H.265 and nothing else: its snapshot endpoint renders a still from
 * the sub stream and takes about 1.5 s to do it, which is why the earlier version of this class could
 * not do better than one frame every few seconds, and why the still never matched the main stream.
 * The moving picture is on the same server, so the panel reads that instead and decodes it here with
 * the TV's own hardware decoder: 10 frames a second instead of one every seven.
 *
 * What the NVR sends is an H.265 stream in a Dahua wrapper: a 38-byte header in front of every frame,
 * then Annex-B NAL units, except that the very first start code of each frame is missing. The panel
 * puts that start code back and hands the frame to MediaCodec.
 *
 * The stream is silent. It is drawn into a TextureView and cropped to fill the card, the way
 * {@code centerCrop} would.
 *
 * Where the NVR is and who may watch it is not in this file: it comes from the launcher's config
 * overlay (config.json in the app's external files directory), so no password ends up in the APK or
 * in the repository. Without those settings there is no camera on the screen.
 */
final class CctvPanel implements TextureView.SurfaceTextureListener {
    private static final String TAG = "CctvPanel";

    /** The Dahua frame header in front of every access unit. */
    private static final int FRAME_HEADER = 38;
    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final int TIMEOUT_MS = 6000;
    /** Wait this long before trying the camera again after the NVR drops the stream. */
    private static final long RETRY_MS = 4000;
    /** The two streams every Dahua channel offers: the recorded picture, and the small one. */
    private static final int MAIN = 0;
    private static final int SUB = 1;
    /**
     * The widest stream this TV can decode while the other panel decodes too.
     *
     * Both cameras are asked for their main stream. The garage camera answers with 2560x1440, and
     * this TV's decoder cannot run that alongside the warehouse camera's 1920x1080: the second
     * picture comes out half green. Either of them alone is fine, and two 1080p streams are fine, so
     * a camera whose main stream is wider than this falls back to its sub stream instead. Set that
     * camera's main stream to 1080p and it will use it again on the next start.
     */
    private static final int MAX_WIDTH = 1920;

    private final Context context;
    private final TextureView surface;
    private final int channel;
    private final String host;
    private final String user;
    private final String pass;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean wanted;
    private volatile HttpURLConnection connection;
    private Thread worker;
    private volatile MediaCodec codec;
    /** The stream this panel is reading: the main one, or the sub one after a downgrade. */
    private volatile int subtype = MAIN;
    /** Set when the panel changed stream by itself, so it reconnects at once instead of waiting. */
    private volatile boolean reconnectNow;
    private int videoWidth, videoHeight;
    private long lastPts;
    /** The digest challenge, kept so a reconnect costs one request instead of two. */
    private String realm, nonce, qop, opaque;

    /**
     * @param host the NVR's address, {@code user} and {@code pass} its login. Empty values mean there
     *             is no camera to show.
     */
    CctvPanel(Context context, TextureView surface, int channel,
              String host, String user, String pass) {
        this.context = context.getApplicationContext();
        this.surface = surface;
        this.channel = channel;
        this.host = host;
        this.user = user;
        this.pass = pass;
        surface.setSurfaceTextureListener(this);
        surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> crop());
    }

    /**
     * True for a host on the local network: a private IPv4 address, an IPv6 unique-local address or
     * localhost. The camera server is a device on the LAN, and the app talks to nothing else, so this
     * keeps the cleartext permission (see the manifest) from ever reaching the internet.
     */
    static boolean isLocalHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
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

    /** Start the camera. Safe to call more than once. */
    void start() {
        if (!isLocalHost(host) || wanted) return;
        wanted = true;
        if (surface.isAvailable()) open();
    }

    /** Stop the camera and free the decoder. */
    void release() {
        wanted = false;
        close();
    }

    /** Called when the activity goes away for good. */
    void destroy() {
        release();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (wanted) open();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        crop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        close();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    private void open() {
        close();
        if (!wanted || surface.getSurfaceTexture() == null) return;
        worker = new Thread(this::loop, "cctv-" + channel);
        worker.setDaemon(true);
        worker.start();
    }

    /** Reads the stream, and keeps reading it: the NVR drops a camera now and then. */
    private void loop() {
        while (wanted) {
            try {
                stream();
            } catch (Exception e) {
                if (wanted) Log.w(TAG, "ch" + channel + " stream stopped: " + e);
            }
            releaseCodec();
            if (!wanted) return;
            if (reconnectNow) {
                reconnectNow = false;
                continue;
            }
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void stream() throws Exception {
        URL url = new URL("http://" + host + "/cgi-bin/mjpg/video.cgi?channel=" + channel
                + "&subtype=" + subtype);
        HttpURLConnection conn = connect(url);
        connection = conn;
        try (InputStream in = conn.getInputStream()) {
            String line;
            while (wanted && (line = readLine(in)) != null) {
                if (!line.startsWith("--")) continue;
                int length = headers(in);
                if (length <= 0) continue;
                byte[] body = new byte[length];
                readFully(in, body);
                frame(body);
            }
        } finally {
            connection = null;
            conn.disconnect();
        }
    }

    /** Reads one part's headers and returns its Content-Length, or -1. */
    private static int headers(InputStream in) throws IOException {
        int length = -1;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return length;
    }

    /**
     * One access unit: drop the Dahua header, put back the start code it swallowed, and hand it to
     * the decoder. The first frame of a stream is a keyframe, and its parameter sets are what the
     * decoder is configured with.
     */
    private void frame(byte[] body) throws Exception {
        if (body.length <= FRAME_HEADER + 2) return;
        byte[] au = new byte[body.length - FRAME_HEADER + 4];
        System.arraycopy(START_CODE, 0, au, 0, 4);
        System.arraycopy(body, FRAME_HEADER, au, 4, body.length - FRAME_HEADER);

        int type = (au[4] >> 1) & 0x3F;
        if (codec == null) {
            if (type != 32) return;                  // wait for a keyframe to configure from
            configure(au);
        }
        queue(au, type >= 16 && type <= 21);
    }

    /** Starts the hardware decoder with the VPS, SPS and PPS of the first keyframe as its csd. */
    private void configure(byte[] au) throws Exception {
        int end = au.length;
        int seen = 0;
        for (int i = 4; i < au.length - 4; i++) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) {
                if (++seen == 3) {                   // the slice follows the third parameter set
                    end = i;
                    break;
                }
            }
        }
        MediaFormat format = MediaFormat.createVideoFormat("video/hevc", 1920, 1080);
        ByteBuffer csd = ByteBuffer.wrap(au, 0, end);
        format.setByteBuffer("csd-0", csd);
        MediaCodec next = MediaCodec.createDecoderByType("video/hevc");
        SurfaceTexture texture = surface.getSurfaceTexture();
        if (texture == null) {
            next.release();
            throw new IllegalStateException("no surface");
        }
        next.configure(format, new Surface(texture), null, 0);
        next.start();
        codec = next;
    }

    private void queue(byte[] au, boolean key) throws Exception {
        MediaCodec decoder = codec;
        if (decoder == null) return;
        int index = decoder.dequeueInputBuffer(8000);
        if (index >= 0) {
            ByteBuffer buffer = decoder.getInputBuffer(index);
            if (buffer != null) {
                buffer.clear();
                buffer.put(au);
                long now = System.nanoTime() / 1000;
                if (now <= lastPts) now = lastPts + 1000;
                lastPts = now;
                decoder.queueInputBuffer(index, 0, au.length, now,
                        key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            }
        }
        drain(decoder);
    }

    /** Hands finished frames to the surface; the size is only known once the decoder says so. */
    private void drain(MediaCodec decoder) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = decoder.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = decoder.getOutputFormat();
                videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                if (videoWidth > MAX_WIDTH && subtype == MAIN) {
                    // Too big to share the decoder with the other camera: read the small one instead.
                    Log.i(TAG, "ch" + channel + " main stream is " + videoWidth
                            + " wide, switching to the sub stream");
                    subtype = SUB;
                    reconnectNow = true;
                    drop();
                    return;
                }
                main.post(this::crop);
            } else if (index >= 0) {
                decoder.releaseOutputBuffer(index, true);
            } else {
                return;
            }
        }
    }

    private void close() {
        drop();
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
    }

    /** Lets go of the stream without stopping the panel, so it reconnects on its next attempt. */
    private void drop() {
        HttpURLConnection conn = connection;
        connection = null;
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
                // already gone
            }
        }
        releaseCodec();
    }

    private void releaseCodec() {
        MediaCodec decoder = codec;
        codec = null;
        videoWidth = videoHeight = 0;
        if (decoder == null) return;
        try {
            decoder.stop();
        } catch (Exception ignored) {
            // never started
        }
        try {
            decoder.release();
        } catch (Exception ignored) {
            // released twice
        }
    }

    /**
     * Fills the card with the picture and cuts off what does not fit, the way {@code centerCrop}
     * would.
     *
     * The matrix handed to {@code setTransform} is applied on top of the mapping the TextureView
     * already does, which stretches the frame to the view. So the scale here is the extra stretch
     * that turns that non-uniform fit into a uniform cover: the wider of the two ratios, expressed
     * against the stretch that is already there.
     */
    private void crop() {
        int viewWidth = surface.getWidth();
        int viewHeight = surface.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) return;
        float viewAspect = (float) viewWidth / viewHeight;
        float videoAspect = (float) videoWidth / videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect;          // the frame is wider: cut the sides
        } else {
            scaleY = viewAspect / videoAspect;          // the frame is taller: cut top and bottom
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(viewWidth * (1f - scaleX) / 2f, viewHeight * (1f - scaleY) / 2f);
        surface.setTransform(matrix);
    }

    /** Opens the stream, answering the NVR's digest challenge by hand: one request, then one retry. */
    private HttpURLConnection connect(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Connection", "keep-alive");
        if (nonce != null) {
            conn.setRequestProperty("Authorization", digest(url));
            if (conn.getResponseCode() == 200) return conn;
            conn.disconnect();
            realm = nonce = qop = opaque = null;
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Connection", "keep-alive");
        }
        int code = conn.getResponseCode();
        if (code == 200) return conn;
        if (code != 401) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        String challenge = conn.getHeaderField("WWW-Authenticate");
        conn.disconnect();
        if (challenge == null) throw new IOException("401 without a challenge");
        realm = field(challenge, "realm");
        nonce = field(challenge, "nonce");
        qop = field(challenge, "qop");
        opaque = field(challenge, "opaque");

        conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("Authorization", digest(url));
        if (conn.getResponseCode() == 200) return conn;
        conn.disconnect();
        throw new IOException("401 with a fresh digest");
    }

    /** RFC 2617 when the NVR offers a qop, RFC 2069 when it does not. */
    private String digest(URL url) {
        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5("GET:" + url.getFile());
        StringBuilder head = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(url.getFile()).append('"');
        if (qop != null && qop.contains("auth")) {
            String cnonce = "0a4f113b";
            String response = md5(ha1 + ":" + nonce + ":00000001:" + cnonce + ":auth:" + ha2);
            head.append(", qop=auth, nc=00000001, cnonce=\"").append(cnonce)
                    .append("\", response=\"").append(response).append('"');
        } else {
            head.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (opaque != null) head.append(", opaque=\"").append(opaque).append('"');
        return head.toString();
    }

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

    /** One header line, without its line ending; null at the end of the stream. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(48);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void readFully(InputStream in, byte[] into) throws IOException {
        int read = 0;
        while (read < into.length) {
            int n = in.read(into, read, into.length - read);
            if (n < 0) throw new IOException("stream ended mid-frame");
            read += n;
        }
    }
}
