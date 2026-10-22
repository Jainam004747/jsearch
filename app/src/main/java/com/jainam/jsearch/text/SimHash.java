package com.jainam.jsearch.text;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SimHash (Charikar, 2002): a 64-bit fingerprint where SIMILAR documents get SIMILAR
 * fingerprints. Normal hashes change completely when one word changes; SimHash changes only a
 * few bits. Two pages whose fingerprints differ in at most 3 of 64 bits are treated as near
 * duplicates (the threshold Google reported for web crawling).
 *
 * <p>How: hash every distinct word (after analysis) to 64 bits. For each bit position, add +1 if the
 * word's hash has that bit set, else -1. The final fingerprint has bit i set where the total for
 * i is positive: a "vote" across all words. Changing one word out of a hundred moves only a few
 * votes, so only a few bits flip. On very short pages a single word is a bigger share of the
 * votes, so near-duplicate detection is less reliable there. (An earlier version hashed 3-word
 * shingles; on short pages one changed word flipped ~15 bits, so it missed obvious copies.)
 */
public final class SimHash {
    private SimHash() { }

    public static long of(String text) {
        List<Analyzer.Token> tokens = Analyzer.analyze(text);
        if (tokens.isEmpty()) {
            return 0;
        }
        int[] votes = new int[64];
        // Each DISTINCT word votes once. Counting repeats would let the most frequent words
        // decide every bit, and unrelated pages with ordinary word statistics would look alike.
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (Analyzer.Token t : tokens) {
            distinct.add(t.term());
        }
        for (String term : distinct) {
            long h = fnv1a64(term);
            for (int bit = 0; bit < 64; bit++) {
                votes[bit] += ((h >>> bit) & 1) == 1 ? 1 : -1;
            }
        }
        long fingerprint = 0;
        for (int bit = 0; bit < 64; bit++) {
            if (votes[bit] > 0) {
                fingerprint |= 1L << bit;
            }
        }
        return fingerprint;
    }

    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    public static boolean nearDuplicate(long a, long b) {
        return distance(a, b) <= 3;
    }

    /**
     * FNV-1a followed by a "finalizer" that mixes every input bit into every output bit.
     *
     * <p>Plain FNV-1a is fine for hash tables (only the low bits are used), but its HIGH bits
     * barely change between short, similar strings. SimHash uses all 64 bits, so without the
     * finalizer the high bits were identical for most words and unrelated pages got nearly the
     * same fingerprint. The benchmark caught this: half of 20,000 random pages were "duplicates".
     */
    static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        // MurmurHash3's fmix64
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
