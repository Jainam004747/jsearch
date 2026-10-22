package com.jainam.jsearch;

import com.jainam.jsearch.index.Document;
import com.jainam.jsearch.index.IndexWriter;
import com.jainam.jsearch.search.Searcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Synthetic benchmark: generates documents whose word frequencies follow Zipf's law (like real
 * text: a few words are very common, most are rare), indexes them, and times queries.
 */
final class Bench {
    private Bench() { }

    static void run(int docs) throws IOException {
        Path dir = Files.createTempDirectory("jsearch-bench");
        Random rnd = new Random(42);
        int vocab = 50_000;
        String[] words = new String[vocab];
        for (int i = 0; i < vocab; i++) {
            words[i] = word(i);
        }
        double[] cdf = zipfCdf(vocab, 1.07);
        long rawBytes = 0;
        long t0 = System.nanoTime();
        try (IndexWriter w = new IndexWriter(dir, 2000)) {
            for (int d = 0; d < docs; d++) {
                StringBuilder sb = new StringBuilder();
                int len = 150 + rnd.nextInt(450);
                for (int k = 0; k < len; k++) {
                    sb.append(words[sample(cdf, rnd)]).append(' ');
                }
                String text = sb.toString();
                rawBytes += text.length();
                w.add(new Document("bench://" + d, words[sample(cdf, rnd)] + " " + words[sample(cdf, rnd)], text));
            }
            w.commit();
            double indexSec = (System.nanoTime() - t0) / 1e9;
            System.out.printf(Locale.ROOT, "indexed %,d docs (%.1f MB of text) in %.2f s: %,.0f docs/s, %d segments, %d near-duplicates%n",
                    docs, rawBytes / 1e6, indexSec, docs / indexSec, w.segmentCount(), w.duplicatesSkipped());
            long m0 = System.nanoTime();
            w.mergeAll();
            System.out.printf(Locale.ROOT, "merged into 1 segment in %.2f s%n", (System.nanoTime() - m0) / 1e9);
        }
        try (Searcher s = new Searcher(dir)) {
            System.out.printf(Locale.ROOT, "index size %.1f MB (%.0f%% of raw text incl. stored text and positions), %,d terms%n",
                    s.sizeOnDisk() / 1e6, 100.0 * s.sizeOnDisk() / rawBytes, s.termCount());
            String[][] kinds = {
                    {"1 rare term", words[20_000]},
                    {"1 common term", words[3]},
                    {"3 mixed terms", words[5] + " " + words[400] + " " + words[9000]},
                    {"phrase", "\"" + words[0] + " " + words[1] + "\""},
            };
            for (String[] k : kinds) {
                long[] times = new long[50];
                long matches = 0;
                for (int i = 0; i < times.length; i++) {
                    long q0 = System.nanoTime();
                    // A different (non-matching) extra word each time, so the query cache never answers.
                    matches = s.search(k[1] + " nomatch" + i, 0, 10).totalMatches();
                    times[i] = System.nanoTime() - q0;
                }
                Arrays.sort(times);
                System.out.printf(Locale.ROOT, "query %-14s %,8d matches   p50 %6.2f ms   p99 %6.2f ms%n", k[0], matches,
                        times[times.length / 2] / 1e6, times[times.length - 1] / 1e6);
            }
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String word(int i) {
        String letters = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder("w");
        do {
            sb.append(letters.charAt(i % 26));
            i /= 26;
        } while (i > 0);
        return sb.toString();
    }

    private static double[] zipfCdf(int n, double s) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += 1 / Math.pow(i + 1, s);
            cdf[i] = sum;
        }
        for (int i = 0; i < n; i++) {
            cdf[i] /= sum;
        }
        return cdf;
    }

    private static int sample(double[] cdf, Random rnd) {
        int i = Arrays.binarySearch(cdf, rnd.nextDouble());
        return i >= 0 ? i : Math.min(cdf.length - 1, -i - 1);
    }
}
