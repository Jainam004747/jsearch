package com.jainam.jsearch.index;

import com.jainam.jsearch.text.Analyzer;
import com.jainam.jsearch.text.SimHash;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds an INVERTED INDEX for a batch of documents in memory, then writes it to disk as an
 * immutable segment.
 *
 * <p>A normal ("forward") index says: document → its words. An inverted index flips it:
 * word → the documents containing it (its "postings list"). Answering "which pages mention
 * espresso?" is then a single lookup instead of reading every page.
 *
 * <pre>
 * Segment files:
 *   terms.dat     sorted terms; for each: document frequency + where its postings start
 *   postings.dat  for each term, for each doc: Δdoc, tf, #positions, Δpositions   (all varints)
 *   docs.dat      per doc: url, title, token count, SimHash, offset of its text
 *   text.dat      the stored document texts (UTF-8), read only when building snippets
 *   deleted.dat   ids of documents replaced by newer versions (written by IndexWriter)
 * </pre>
 *
 * Segments are never modified after they are written (except deleted.dat). New documents go
 * into new segments, and small segments are later merged into bigger ones, the same design
 * Lucene and Elasticsearch use (a "log-structured merge" approach).
 */
public final class SegmentWriter {
    /** A word in the title counts like this many words in the body. */
    public static final int TITLE_WEIGHT = 3;
    /** Gap between title and body positions, so a phrase cannot span from one into the other. */
    static final int BODY_POSITION_START = 1000;

    record DocInfo(Document doc, int length, long simhash) { }

    private final List<DocInfo> docs = new ArrayList<>();
    private final Map<String, List<Posting>> postings = new HashMap<>();

    public int size() {
        return docs.size();
    }

    /** Analyzes a document and adds it to the in-memory index. Returns its local id. */
    public int add(Document doc, long simhash) {
        int id = docs.size();
        List<Analyzer.Token> title = Analyzer.analyze(doc.title(), 0);
        List<Analyzer.Token> body = Analyzer.analyze(doc.text(), BODY_POSITION_START);
        Map<String, List<Integer>> positions = new TreeMap<>();
        Map<String, Integer> tf = new HashMap<>();
        for (Analyzer.Token t : title) {
            positions.computeIfAbsent(t.term(), k -> new ArrayList<>()).add(t.position());
            tf.merge(t.term(), TITLE_WEIGHT, Integer::sum);
        }
        for (Analyzer.Token t : body) {
            positions.computeIfAbsent(t.term(), k -> new ArrayList<>()).add(t.position());
            tf.merge(t.term(), 1, Integer::sum);
        }
        for (Map.Entry<String, List<Integer>> e : positions.entrySet()) {
            int[] pos = e.getValue().stream().mapToInt(Integer::intValue).toArray();
            postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new Posting(id, tf.get(e.getKey()), pos));
        }
        docs.add(new DocInfo(doc, title.size() * TITLE_WEIGHT + body.size(), simhash));
        return id;
    }

    public int add(Document doc) {
        return add(doc, SimHash.of(doc.text()));
    }

    /** Writes the segment into {@code dir} (which must not exist yet). */
    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<String> terms = new ArrayList<>(postings.keySet());
        terms.sort(null);
        try (DataOutputStream termOut = open(dir.resolve("terms.dat"));
             OutputStream postOut = new BufferedOutputStream(Files.newOutputStream(dir.resolve("postings.dat")))) {
            termOut.writeInt(terms.size());
            long offset = 0;
            for (String term : terms) {
                byte[] encoded = encodePostings(postings.get(term));
                termOut.writeUTF(term);
                termOut.writeInt(postings.get(term).size());
                termOut.writeLong(offset);
                termOut.writeInt(encoded.length);
                postOut.write(encoded);
                offset += encoded.length;
            }
        }
        writeDocs(dir, docs);
    }

    static byte[] encodePostings(List<Posting> list) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prevDoc = 0;
        for (Posting p : list) {
            VarInt.write(out, p.doc() - prevDoc); // gap from the previous doc id
            prevDoc = p.doc();
            VarInt.write(out, p.tf());
            VarInt.write(out, p.positions().length);
            int prevPos = 0;
            for (int pos : p.positions()) {
                VarInt.write(out, pos - prevPos);
                prevPos = pos;
            }
        }
        return out.toByteArray();
    }

    static void writeDocs(Path dir, List<DocInfo> docs) throws IOException {
        try (DataOutputStream docOut = open(dir.resolve("docs.dat"));
             OutputStream textOut = new BufferedOutputStream(Files.newOutputStream(dir.resolve("text.dat")))) {
            docOut.writeInt(docs.size());
            long textOffset = 0;
            for (DocInfo d : docs) {
                byte[] text = d.doc().text().getBytes(StandardCharsets.UTF_8);
                docOut.writeUTF(clip(d.doc().url()));
                docOut.writeUTF(clip(d.doc().title()));
                docOut.writeInt(d.length());
                docOut.writeLong(d.simhash());
                docOut.writeLong(textOffset);
                docOut.writeInt(text.length);
                textOut.write(text);
                textOffset += text.length;
            }
        }
        Files.write(dir.resolve("deleted.dat"), new byte[] {0, 0, 0, 0});
    }

    /** writeUTF is limited to 65,535 bytes; titles and URLs never need that much. */
    private static String clip(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static DataOutputStream open(Path p) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)));
    }
}
