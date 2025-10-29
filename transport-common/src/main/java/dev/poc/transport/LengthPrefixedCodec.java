package dev.poc.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Codec that writes and reads frames that start with a four-byte big-endian length followed by
 * UTF-8 encoded JSON text.
 */
public final class LengthPrefixedCodec {

    private LengthPrefixedCodec() {
    }

    public static void writeFrame(OutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        if (payload.length > 0x7FFFFFFF) {
            throw new IOException("Frame too large: " + payload.length);
        }
        byte[] header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length).array();
        out.write(header);
        out.write(payload);
        out.flush();
    }

    public static String readFrame(InputStream in) throws IOException {
        byte[] header = readFully(in, 4);
        if (header == null) {
            return null; // EOF before header indicates clean shutdown.
        }
        int length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt();
        if (length < 0) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] payload = readFully(in, length);
        if (payload == null) {
            throw new EOFException("Stream closed while reading frame payload of length " + length);
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1) {
                if (offset == 0) {
                    return null;
                }
                throw new EOFException("Unexpected end of stream after reading " + offset + " bytes");
            }
            offset += read;
        }
        return buffer;
    }
}
