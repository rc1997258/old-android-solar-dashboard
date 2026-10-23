package com.diy.tapologger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TP-Link Tapo local "KLAP" protocol (v2 hashes), as used by the P110 when
 * "Third-Party Compatibility" is ON in the Tapo app.
 *
 * Mirrors python-kasa's KlapTransportV2 / KlapEncryptionSession:
 *   auth_hash  = sha256(sha1(email) + sha1(password))
 *   handshake1 : POST /app/handshake1  body=local_seed(16)
 *                reply = remote_seed(16) + sha256(local + remote + auth_hash)
 *   handshake2 : POST /app/handshake2  body=sha256(remote + local + auth_hash)
 *   key = sha256("lsk"+l+r+h)[:16], iv = sha256("iv"+l+r+h)[:12],
 *   seq = last 4 bytes of that iv hash (signed), sig = sha256("ldk"+l+r+h)[:28]
 *   request    : POST /app/request?seq=N
 *                body = sha256(sig + seq + ct) + ct, ct = AES-CBC(key, iv+seq, json)
 *
 * Pure java.* only (no Android classes) so it can be unit-tested on a desktop JVM.
 */
public class Klap {

    public static class KlapException extends IOException {
        public KlapException(String msg) { super(msg); }
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern COOKIE = Pattern.compile("TP_SESSIONID=([^;\\s]+)");
    private static final long SESSION_MAX_MS = 20L * 3600 * 1000;  // device cookie lasts 24 h

    private final String host;
    private final byte[] authHash;
    private final String terminalUuid;

    private byte[] key, ivBase, sig;
    private int seq;
    private String cookie;
    private long sessionStart;
    private boolean ready;

    public Klap(String host, byte[] authHash) {
        this.host = host.trim();
        this.authHash = authHash;
        this.terminalUuid = base64(md5(UUID.randomUUID().toString().getBytes()));
    }

    // ------------------------------------------------------------ public API

    /** sha256(sha1(email) + sha1(password)). Both are case-sensitive. */
    public static byte[] authHash(String email, String password) {
        try {
            return sha256(cat(sha1(email.getBytes("UTF-8")), sha1(password.getBytes("UTF-8"))));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Send one Tapo "smart" request; returns the decrypted JSON reply as text. */
    public synchronized String call(String method, String paramsJson) throws IOException {
        String req = "{\"method\":\"" + method + "\""
                + (paramsJson != null ? ",\"params\":" + paramsJson : "")
                + ",\"request_time_milis\":" + System.currentTimeMillis()
                + ",\"terminal_uuid\":\"" + terminalUuid + "\"}";
        return request(req);
    }

    /** Forget the session; the next call does a fresh handshake. */
    public synchronized void reset() { ready = false; cookie = null; }

    // ------------------------------------------------------------ protocol

    private void handshake() throws IOException {
        ready = false;
        cookie = null;
        byte[] local = new byte[16];
        RNG.nextBytes(local);

        Resp r1 = post("/app/handshake1", local, null);
        if (r1.code == 403 || r1.code == 401)
            throw new KlapException("Plug refused login (HTTP " + r1.code + "). In the Tapo app turn ON "
                    + "Me > Third-Party Services > Third-Party Compatibility, wait 1 min, retry.");
        if (r1.code == 404)
            throw new KlapException("Plug does not speak KLAP (HTTP 404). Update its firmware in the Tapo app.");
        if (r1.code != 200)
            throw new KlapException("handshake1 failed: HTTP " + r1.code);
        if (r1.body.length != 48)
            throw new KlapException("handshake1: unexpected reply length " + r1.body.length);

        byte[] remote = slice(r1.body, 0, 16);
        byte[] serverHash = slice(r1.body, 16, 48);
        if (!MessageDigest.isEqual(sha256(cat(local, remote, authHash)), serverHash))
            throw new KlapException("Wrong Tapo e-mail or password (both are case-sensitive).");

        cookie = r1.cookie;
        Resp r2 = post("/app/handshake2", sha256(cat(remote, local, authHash)), cookie);
        if (r2.code != 200)
            throw new KlapException("handshake2 failed: HTTP " + r2.code);

        key = slice(sha256(cat(ascii("lsk"), local, remote, authHash)), 0, 16);
        byte[] fullIv = sha256(cat(ascii("iv"), local, remote, authHash));
        ivBase = slice(fullIv, 0, 12);
        seq = ((fullIv[28] & 0xff) << 24) | ((fullIv[29] & 0xff) << 16)
                | ((fullIv[30] & 0xff) << 8) | (fullIv[31] & 0xff);
        sig = slice(sha256(cat(ascii("ldk"), local, remote, authHash)), 0, 28);
        sessionStart = System.currentTimeMillis();
        ready = true;
    }

    private String request(String json) throws IOException {
        if (!ready || System.currentTimeMillis() - sessionStart > SESSION_MAX_MS)
            handshake();
        try {
            seq++;
            byte[] seqBytes = int4(seq);
            byte[] iv = cat(ivBase, seqBytes);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ct = c.doFinal(json.getBytes("UTF-8"));
            byte[] payload = cat(sha256(cat(sig, seqBytes, ct)), ct);

            Resp r = post("/app/request?seq=" + seq, payload, cookie);
            if (r.code != 200) {
                ready = false;
                throw new KlapException("request failed: HTTP " + r.code + " (will re-login)");
            }
            if (r.body.length <= 32) {
                ready = false;
                throw new KlapException("request: empty reply");
            }
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(r.body, 32, r.body.length - 32), "UTF-8");
        } catch (IOException e) {
            ready = false;
            throw e;
        } catch (Exception e) {
            ready = false;
            throw new KlapException("crypto error: " + e);
        }
    }

    // ------------------------------------------------------------ HTTP

    private static class Resp {
        int code;
        byte[] body;
        String cookie;
    }

    private Resp post(String path, byte[] body, String sessionCookie) throws IOException {
        URL url = new URL("http://" + host + path);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        try {
            con.setConnectTimeout(5000);
            con.setReadTimeout(8000);
            con.setUseCaches(false);
            con.setInstanceFollowRedirects(false);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/octet-stream");
            con.setRequestProperty("Accept", "*/*");
            if (sessionCookie != null)
                con.setRequestProperty("Cookie", "TP_SESSIONID=" + sessionCookie);
            con.setFixedLengthStreamingMode(body.length);
            OutputStream os = con.getOutputStream();
            os.write(body);
            os.close();

            Resp r = new Resp();
            r.code = con.getResponseCode();
            InputStream in = r.code >= 400 ? con.getErrorStream() : con.getInputStream();
            r.body = in != null ? readAll(in) : new byte[0];
            for (Map.Entry<String, List<String>> h : con.getHeaderFields().entrySet()) {
                if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String v : h.getValue()) {
                        Matcher m = COOKIE.matcher(v);
                        if (m.find()) r.cookie = m.group(1);
                    }
                }
            }
            return r;
        } finally {
            con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------ helpers

    static byte[] sha256(byte[] b) { return digest("SHA-256", b); }
    static byte[] sha1(byte[] b) { return digest("SHA-1", b); }
    static byte[] md5(byte[] b) { return digest("MD5", b); }

    private static byte[] digest(String alg, byte[] b) {
        try {
            return MessageDigest.getInstance(alg).digest(b);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    static byte[] int4(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xff) << 16;
            if (i + 1 < d.length) b |= (d[i + 1] & 0xff) << 8;
            if (i + 2 < d.length) b |= d[i + 2] & 0xff;
            sb.append(B64[(b >> 18) & 63]).append(B64[(b >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(b >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }
}
