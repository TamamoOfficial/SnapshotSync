package com.modsync.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal line-based protocol for fetching mod jars over a raw TCP tunnel.
 *
 * <p>HTTP cannot be used here: some NAT/tunnel providers (SakuraFrp among them) forward only
 * plain TCP and intercept anything that looks like an HTTP request, answering it themselves.
 * A connection starting with {@code MODSYNC1} is not HTTP, so it passes through untouched.
 *
 * <h2>Wire format</h2>
 * <pre>
 * client -> server : MODSYNC1 LIST\n
 *                    MODSYNC1 GET &lt;name&gt;\n
 *
 * server -> client : List reply
 *                      int32 count
 *                      per file: int32 nameLen, name bytes, int64 size, int64 reserved
 *                    File reply
 *                      int32 status (0 = ok)
 *                      int64 size, then raw bytes
 * </pre>
 */
public final class ModSyncProtocol {

    /** Magic prefix; deliberately not an HTTP method or an MC packet shape. */
    public static final String MAGIC = "MODSYNC1";

    public static final String VERB_LIST = "LIST";
    public static final String VERB_GET = "GET";

    private ModSyncProtocol() {
    }

    /** Reads the first request line (magic + verb [+ argument]). */
    public static String readLine(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        return reader.readLine();
    }

    /** True when the first bytes look like this protocol rather than HTTP or a MC handshake. */
    public static boolean startsWithMagic(byte[] head, int len) {
        byte[] magic = MAGIC.getBytes(StandardCharsets.US_ASCII);
        if (len < magic.length) {
            // Compare what we have so far; a partial prefix still counts as a candidate.
            for (int i = 0; i < len; i++) {
                if (head[i] != magic[i]) {
                    return false;
                }
            }
            return len > 0;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    public static void writeInt(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static void writeLong(OutputStream out, long v) throws IOException {
        writeInt(out, (int) (v >>> 32));
        writeInt(out, (int) v);
    }

    public static int readInt(InputStream in) throws IOException {
        int a = in.read();
        int b = in.read();
        int c = in.read();
        int d = in.read();
        if (d < 0) {
            throw new IOException("eof");
        }
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    public static long readLong(InputStream in) throws IOException {
        long hi = readInt(in) & 0xFFFFFFFFL;
        long lo = readInt(in) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }
}
