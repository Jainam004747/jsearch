package com.jainam.jsearch.index;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Variable-length integers ("varints"): small numbers take fewer bytes.
 *
 * <p>Each byte stores 7 bits of the number; the high bit says "more bytes follow".
 * 5 → 1 byte, 300 → 2 bytes, 2,000,000 → 3 bytes.
 *
 * <p>Combined with DELTA ENCODING this shrinks the index dramatically. Postings are sorted doc
 * ids, so instead of [1000, 1003, 1010] we store the gaps [1000, 3, 7], and most gaps are tiny.
 * The same trick is used for word positions inside a document.
 */
public final class VarInt {
    private VarInt() { }

    public static void write(ByteArrayOutputStream out, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("varints must be non-negative: " + value);
        }
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    public static int read(ByteBuffer in) {
        int result = 0;
        int shift = 0;
        while (true) {
            byte b = in.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 28) {
                throw new IllegalStateException("corrupt varint in index");
            }
        }
    }
}
