package com.jainam.jsearch.index;

import com.jainam.jsearch.text.SimHash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Adds documents to an index directory.
 *
 * <ul>
 *   <li>New documents are buffered in memory and written as a new segment every
 *       {@code maxBufferedDocs} documents or on {@link #commit()}.</li>
 *   <li>Adding a URL that is already indexed REPLACES it: the old copy is marked deleted.</li>
 *   <li>Near-duplicate pages (SimHash distance ≤ 3) are skipped.</li>
 *   <li>When there are too many segments, they are merged into one.</li>
 * </ul>
 *
 * Not thread-safe: the crawler calls it from one synchronized place.
 */
public final class IndexWriter implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(IndexWriter.class.getName());
    static final int MERGE_THRESHOLD = 8;

    public enum Result { ADDED, REPLACED, DUPLICATE, EMPTY }

    private record Location(SegmentReader segment, int doc) { }

    private final Path dir;
    private final int maxBufferedDocs;
    private Manifest manifest;
    private final List<SegmentReader> segments = new ArrayList<>();
    private final Map<String, Location> byUrl = new HashMap<>();
    private final LinkedHashMap<String, Document> buffer = new LinkedHashMap<>();
    private final Map<String, Long> bufferSimhash = new HashMap<>();
    private final Set<SegmentReader> dirtyDeletes = new HashSet<>();
    private int duplicates;

    public IndexWriter(Path dir) throws IOException {
        this(dir, 500);
    }

    public IndexWriter(Path dir, int maxBufferedDocs) throws IOException {
        this.dir = dir;
        this.maxBufferedDocs = maxBufferedDocs;
        Files.createDirectories(dir);
        manifest = Manifest.load(dir);
        for (String name : manifest.segments()) {
            SegmentReader r = new SegmentReader(dir.resolve(name));
            segments.add(r);
            for (int d = 0; d < r.maxDoc(); d++) {
                if (!r.isDeleted(d)) {
                    byUrl.put(r.url(d), new Location(r, d));
                }
            }
        }
    }

    public Result add(Document doc) throws IOException {
        if (doc.text().isBlank() && doc.title().isBlank()) {
            return Result.EMPTY;
        }
        long hash = SimHash.of(doc.text()); // body only: mirrors often change the title
        if (isNearDuplicate(doc.url(), hash)) {
            duplicates++;
            return Result.DUPLICATE;
        }
        Result result = Result.ADDED;
        Location old = byUrl.remove(doc.url());
        if (old != null) {
            old.segment().markDeleted(old.doc());
            dirtyDeletes.add(old.segment());
            result = Result.REPLACED;
        }
        if (buffer.put(doc.url(), doc) != null) {
            result = Result.REPLACED;
        }
        bufferSimhash.put(doc.url(), hash);
        if (buffer.size() >= maxBufferedDocs) {
            flush();
        }
        return result;
    }

    /**
     * Linear scan over all fingerprints: fine for a personal index (thousands of pages). At web
     * scale you would split fingerprints into bands and look up candidates in hash tables (LSH).
     */
    private boolean isNearDuplicate(String url, long hash) {
        for (Map.Entry<String, Long> e : bufferSimhash.entrySet()) {
            if (!e.getKey().equals(url) && SimHash.nearDuplicate(e.getValue(), hash)) {
                return true;
            }
        }
        for (Map.Entry<String, Location> e : byUrl.entrySet()) {
            Location loc = e.getValue();
            if (!e.getKey().equals(url) && SimHash.nearDuplicate(loc.segment().simhash(loc.doc()), hash)) {
                return true;
            }
        }
        return false;
    }

    public int duplicatesSkipped() {
        return duplicates;
    }

    /** Writes buffered documents as a new segment (not yet visible until commit). */
    private void flush() throws IOException {
        if (buffer.isEmpty()) {
            return;
        }
        SegmentWriter w = new SegmentWriter();
        for (Document d : buffer.values()) {
            w.add(d, bufferSimhash.get(d.url()));
        }
        String name = String.format("seg_%06d", manifest.nextSegment());
        w.write(dir.resolve(name));
        List<String> segs = new ArrayList<>(manifest.segments());
        segs.add(name);
        manifest = new Manifest(List.copyOf(segs), manifest.nextSegment() + 1);
        SegmentReader r = new SegmentReader(dir.resolve(name));
        segments.add(r);
        for (int d = 0; d < r.maxDoc(); d++) {
            byUrl.put(r.url(d), new Location(r, d));
        }
        buffer.clear();
        bufferSimhash.clear();
    }

    /** Makes everything added so far durable and visible to new searchers. */
    public void commit() throws IOException {
        flush();
        // Publish the new segments first, then the deletions of the old copies they replace.
        // A crash in between leaves a page indexed twice (harmless), never missing.
        manifest.save(dir);
        for (SegmentReader r : dirtyDeletes) {
            r.writeDeleted();
        }
        dirtyDeletes.clear();
        if (segments.size() > MERGE_THRESHOLD) {
            mergeAll();
        }
        deleteUnusedSegments();
    }

    /** Merges every segment into one ("optimize"). */
    public void mergeAll() throws IOException {
        flush();
        for (SegmentReader r : dirtyDeletes) {
            r.writeDeleted();
        }
        dirtyDeletes.clear();
        if (segments.size() <= 1 && segments.stream().allMatch(s -> s.liveDocs() == s.maxDoc())) {
            manifest.save(dir);
            return;
        }
        String name = String.format("seg_%06d", manifest.nextSegment());
        long t0 = System.nanoTime();
        Merger.merge(segments, dir.resolve(name));
        int before = segments.size();
        for (SegmentReader r : segments) {
            r.close();
        }
        segments.clear();
        byUrl.clear();
        manifest = new Manifest(List.of(name), manifest.nextSegment() + 1);
        manifest.save(dir); // the switch to the merged segment is this one atomic rename
        SegmentReader merged = new SegmentReader(dir.resolve(name));
        segments.add(merged);
        for (int d = 0; d < merged.maxDoc(); d++) {
            byUrl.put(merged.url(d), new Location(merged, d));
        }
        LOG.info(String.format("merged %d segments into %s (%d docs) in %d ms", before, name, merged.maxDoc(),
                (System.nanoTime() - t0) / 1_000_000));
        deleteUnusedSegments();
    }

    /** Removes segment directories that the manifest no longer lists (old, merged-away ones). */
    private void deleteUnusedSegments() throws IOException {
        Set<String> live = new HashSet<>(manifest.segments());
        try (Stream<Path> dirs = Files.list(dir)) {
            for (Path p : dirs.filter(Files::isDirectory).toList()) {
                if (p.getFileName().toString().startsWith("seg_") && !live.contains(p.getFileName().toString())) {
                    try (Stream<Path> files = Files.walk(p)) {
                        for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(f);
                        }
                    }
                }
            }
        }
    }

    public int segmentCount() {
        return segments.size() + (buffer.isEmpty() ? 0 : 1);
    }

    @Override
    public void close() throws IOException {
        commit();
        for (SegmentReader r : segments) {
            r.close();
        }
    }
}
