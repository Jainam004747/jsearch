package com.jainam.jsearch.search;

import com.jainam.jsearch.index.Manifest;
import com.jainam.jsearch.index.Posting;
import com.jainam.jsearch.index.SegmentReader;
import com.jainam.jsearch.text.UrlNormalizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Answers queries against a snapshot of the index (the segments listed in the manifest when it
 * was opened), ranking results with BM25.
 *
 * <h2>BM25</h2>
 * For each query term t and document d:
 * <pre>
 *   score(t, d) = idf(t) · tf · (k1 + 1) / (tf + k1 · (1 − b + b · |d| / avgdl))
 *   idf(t)      = ln(1 + (N − df + 0.5) / (df + 0.5))
 * </pre>
 * <ul>
 *   <li><b>idf</b>: rare words matter more. "the" is everywhere; "espresso" is informative.</li>
 *   <li><b>tf saturation (k1 = 1.2)</b>: the 10th "coffee" in a page adds much less than the 1st,
 *       so keyword stuffing does not pay.</li>
 *   <li><b>length normalisation (b = 0.75)</b>: one match in a short page is stronger evidence than
 *       one match in a very long page.</li>
 * </ul>
 *
 * Results are kept in a min-heap of size k, so finding the top 10 of a million matches costs
 * O(n log 10), not a full sort. Recent queries are cached (LRU).
 */
public final class Searcher implements AutoCloseable {
    static final double K1 = 1.2;
    static final double B = 0.75;

    private final List<SegmentReader> segments = new ArrayList<>();
    private final long totalDocs;
    private final double avgLength;
    private final Map<String, Results> cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Results> eldest) {
            return size() > 256; // least recently used query is dropped
        }
    });

    /** One search hit. {@code snippet} is HTML with matching words in {@code <b>}. */
    public record Hit(String url, String title, String snippet, double score) { }

    public record Results(String query, long totalMatches, List<Hit> hits, double millis, boolean cached) { }

    public Searcher(Path indexDir) throws IOException {
        Manifest m = Manifest.load(indexDir);
        long docs = 0;
        long length = 0;
        for (String name : m.segments()) {
            SegmentReader r = new SegmentReader(indexDir.resolve(name));
            segments.add(r);
            for (int d = 0; d < r.maxDoc(); d++) {
                if (!r.isDeleted(d)) {
                    docs++;
                    length += r.length(d);
                }
            }
        }
        totalDocs = docs;
        avgLength = docs == 0 ? 1 : (double) length / docs;
    }

    public long totalDocs() {
        return totalDocs;
    }

    public int segmentCount() {
        return segments.size();
    }

    public long termCount() {
        long n = 0;
        for (SegmentReader s : segments) {
            n += s.termCount();
        }
        return n;
    }

    public long sizeOnDisk() throws IOException {
        long n = 0;
        for (SegmentReader s : segments) {
            n += s.sizeOnDisk();
        }
        return n;
    }

    public Results search(String queryText, int offset, int limit) throws IOException {
        String key = queryText.strip() + "\u0000" + offset + "\u0000" + limit;
        Results cached = cache.get(key);
        if (cached != null) {
            return new Results(cached.query(), cached.totalMatches(), cached.hits(), 0, true);
        }
        long t0 = System.nanoTime();
        Query q = Query.parse(queryText);
        Results r = q.isEmpty() ? new Results(queryText, 0, List.of(), 0, false) : run(queryText, q, offset, limit);
        r = new Results(r.query(), r.totalMatches(), r.hits(), (System.nanoTime() - t0) / 1e6, false);
        cache.put(key, r);
        return r;
    }

    private record Scored(int segment, int doc, double score) { }

    private Results run(String text, Query q, int offset, int limit) throws IOException {
        int k = offset + limit;
        PriorityQueue<Scored> top = new PriorityQueue<>((a, b) -> Double.compare(a.score(), b.score()));
        long matches = 0;
        List<String> scoring = q.scoringTerms();

        for (int s = 0; s < segments.size(); s++) {
            SegmentReader seg = segments.get(s);
            Map<Integer, Double> scores = new HashMap<>();
            Map<String, Map<Integer, Posting>> byTerm = new HashMap<>();
            for (String term : scoring) {
                double idf = idf(term);
                Map<Integer, Posting> docs = new HashMap<>();
                for (Posting p : seg.postings(term)) {
                    if (seg.isDeleted(p.doc())) {
                        continue;
                    }
                    docs.put(p.doc(), p);
                    double norm = K1 * (1 - B + B * seg.length(p.doc()) / avgLength);
                    double score = idf * p.tf() * (K1 + 1) / (p.tf() + norm);
                    scores.merge(p.doc(), score, Double::sum);
                }
                byTerm.put(term, docs);
            }
            java.util.Set<Integer> excludedDocs = new java.util.HashSet<>();
            for (String ex : q.excluded()) {
                for (Posting p : seg.postings(ex)) {
                    excludedDocs.add(p.doc());
                }
            }
            for (Map.Entry<Integer, Double> e : scores.entrySet()) {
                int doc = e.getKey();
                if (excludedDocs.contains(doc) || !passesFilters(q, seg, doc, byTerm)) {
                    continue;
                }
                double score = e.getValue();
                if (!q.phrases().isEmpty()) {
                    score *= 1.5; // reward exact phrase matches
                }
                matches++;
                top.add(new Scored(s, doc, score));
                if (top.size() > k) {
                    top.poll(); // drop the lowest of the k+1
                }
            }
        }

        List<Scored> ranked = new ArrayList<>(top);
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Hit> hits = new ArrayList<>();
        for (int i = offset; i < ranked.size(); i++) {
            Scored sc = ranked.get(i);
            SegmentReader seg = segments.get(sc.segment());
            String title = seg.title(sc.doc()).isBlank() ? seg.url(sc.doc()) : seg.title(sc.doc());
            hits.add(new Hit(seg.url(sc.doc()), title, Snippets.make(seg.text(sc.doc()), scoring), sc.score()));
        }
        return new Results(text, matches, hits, 0, false);
    }

    private boolean passesFilters(Query q, SegmentReader seg, int doc, Map<String, Map<Integer, Posting>> byTerm) {
        if (q.site() != null) {
            String host = UrlNormalizer.host(seg.url(doc));
            if (!host.equals(q.site()) && !host.endsWith("." + q.site())) {
                return false;
            }
        }
        for (Query.Phrase phrase : q.phrases()) {
            if (!containsPhrase(phrase, doc, byTerm)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A phrase matches if some position p has term 0 at p, and every other term i at
     * p + offset[i]. Stop words were removed at indexing time but still advanced the position
     * counter, so "cup of coffee" is checked as "cup" at p and "coffee" at p + 2.
     */
    static boolean containsPhrase(Query.Phrase phrase, int doc, Map<String, Map<Integer, Posting>> byTerm) {
        List<String> terms = phrase.terms();
        Posting first = byTerm.getOrDefault(terms.get(0), Map.of()).get(doc);
        if (first == null) {
            return false;
        }
        outer:
        for (int start : first.positions()) {
            for (int i = 1; i < terms.size(); i++) {
                Posting p = byTerm.getOrDefault(terms.get(i), Map.of()).get(doc);
                if (p == null || java.util.Arrays.binarySearch(p.positions(), start + phrase.offsets()[i]) < 0) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    double idf(String term) {
        long df = 0;
        for (SegmentReader s : segments) {
            df += s.docFreq(term);
        }
        return Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));
    }

    @Override
    public void close() throws IOException {
        for (SegmentReader s : segments) {
            s.close();
        }
    }
}
