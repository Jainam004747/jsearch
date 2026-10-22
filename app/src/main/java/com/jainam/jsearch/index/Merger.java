package com.jainam.jsearch.index;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Merges several segments into one, dropping deleted documents.
 *
 * <p>Every segment's term dictionary is already sorted, so the merged dictionary is produced by
 * a K-WAY MERGE (like the merge step of merge sort, over K lists at once, using a priority
 * queue). For each term, the postings of the input segments are concatenated, with document ids
 * renumbered into the new segment's id space. Nothing is re-tokenized.
 *
 * <pre>
 *   segment A (2 docs):  "coffee" → [0, 1]        new ids: A0→0, A1→1
 *   segment B (2 docs):  "coffee" → [1]           new ids: B0→2 (B1 was deleted)
 *   merged:              "coffee" → [0, 1]        (B's doc 1 is gone)
 * </pre>
 */
public final class Merger {
    private Merger() { }

    private record Cursor(String term, int segment, int index) implements Comparable<Cursor> {
        @Override
        public int compareTo(Cursor o) {
            int c = term.compareTo(o.term);
            return c != 0 ? c : Integer.compare(segment, o.segment);
        }
    }

    public static void merge(List<SegmentReader> segments, Path out) throws IOException {
        // 1. Assign new document ids, skipping deleted documents.
        int[][] docMap = new int[segments.size()][];
        List<SegmentWriter.DocInfo> docs = new ArrayList<>();
        for (int s = 0; s < segments.size(); s++) {
            SegmentReader r = segments.get(s);
            docMap[s] = new int[r.maxDoc()];
            for (int d = 0; d < r.maxDoc(); d++) {
                if (r.isDeleted(d)) {
                    docMap[s][d] = -1;
                } else {
                    docMap[s][d] = docs.size();
                    docs.add(new SegmentWriter.DocInfo(new Document(r.url(d), r.title(d), r.text(d)), r.length(d),
                            r.simhash(d)));
                }
            }
        }

        // 2. K-way merge of the sorted term dictionaries.
        Files.createDirectories(out);
        PriorityQueue<Cursor> heap = new PriorityQueue<>();
        for (int s = 0; s < segments.size(); s++) {
            if (segments.get(s).termCount() > 0) {
                heap.add(new Cursor(segments.get(s).term(0), s, 0));
            }
        }
        List<String> terms = new ArrayList<>();
        List<byte[]> encoded = new ArrayList<>();
        while (!heap.isEmpty()) {
            String term = heap.peek().term();
            List<Posting> merged = new ArrayList<>();
            // Pull this term from every segment that has it. Segment order keeps new ids ascending.
            while (!heap.isEmpty() && heap.peek().term().equals(term)) {
                Cursor c = heap.poll();
                for (Posting p : segments.get(c.segment()).postingsAt(c.index())) {
                    int newId = docMap[c.segment()][p.doc()];
                    if (newId >= 0) {
                        merged.add(new Posting(newId, p.tf(), p.positions()));
                    }
                }
                SegmentReader r = segments.get(c.segment());
                if (c.index() + 1 < r.termCount()) {
                    heap.add(new Cursor(r.term(c.index() + 1), c.segment(), c.index() + 1));
                }
            }
            if (!merged.isEmpty()) { // a term used only by deleted documents disappears
                terms.add(term);
                encoded.add(SegmentWriter.encodePostings(merged));
            }
        }

        // 3. Write the new segment.
        try (DataOutputStream termOut = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(out.resolve("terms.dat"))));
             OutputStream postOut = new BufferedOutputStream(Files.newOutputStream(out.resolve("postings.dat")))) {
            termOut.writeInt(terms.size());
            long offset = 0;
            for (int i = 0; i < terms.size(); i++) {
                byte[] e = encoded.get(i);
                termOut.writeUTF(terms.get(i));
                termOut.writeInt(countDocs(e));
                termOut.writeLong(offset);
                termOut.writeInt(e.length);
                postOut.write(e);
                offset += e.length;
            }
        }
        SegmentWriter.writeDocs(out, docs);
    }

    /** Counts postings in an encoded list without fully materialising it. */
    private static int countDocs(byte[] encoded) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(encoded);
        int n = 0;
        while (buf.hasRemaining()) {
            VarInt.read(buf);
            VarInt.read(buf);
            int npos = VarInt.read(buf);
            for (int k = 0; k < npos; k++) {
                VarInt.read(buf);
            }
            n++;
        }
        return n;
    }
}
