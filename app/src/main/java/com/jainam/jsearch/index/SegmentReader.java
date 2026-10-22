package com.jainam.jsearch.index;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Reads one segment. The term dictionary and document metadata are loaded into memory (they are
 * small); the postings file is memory-mapped, so the operating system pages in only the parts a
 * query actually touches; stored texts are read on demand for snippets.
 */
public final class SegmentReader implements AutoCloseable {
    private final Path dir;
    private final String[] terms;       // sorted, for binary search
    private final int[] docFreq;
    private final long[] postingsOffset;
    private final int[] postingsLength;
    private final MappedByteBuffer postings;
    private final FileChannel textChannel;
    private final String[] urls;
    private final String[] titles;
    private final int[] lengths;
    private final long[] simhashes;
    private final long[] textOffset;
    private final int[] textLength;
    private final BitSet deleted = new BitSet();

    public SegmentReader(Path dir) throws IOException {
        this.dir = dir;
        try (DataInputStream in = open(dir.resolve("terms.dat"))) {
            int n = in.readInt();
            terms = new String[n];
            docFreq = new int[n];
            postingsOffset = new long[n];
            postingsLength = new int[n];
            for (int i = 0; i < n; i++) {
                terms[i] = in.readUTF();
                docFreq[i] = in.readInt();
                postingsOffset[i] = in.readLong();
                postingsLength[i] = in.readInt();
            }
        }
        try (FileChannel ch = FileChannel.open(dir.resolve("postings.dat"))) {
            postings = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        try (DataInputStream in = open(dir.resolve("docs.dat"))) {
            int n = in.readInt();
            urls = new String[n];
            titles = new String[n];
            lengths = new int[n];
            simhashes = new long[n];
            textOffset = new long[n];
            textLength = new int[n];
            for (int i = 0; i < n; i++) {
                urls[i] = in.readUTF();
                titles[i] = in.readUTF();
                lengths[i] = in.readInt();
                simhashes[i] = in.readLong();
                textOffset[i] = in.readLong();
                textLength[i] = in.readInt();
            }
        }
        textChannel = FileChannel.open(dir.resolve("text.dat"), StandardOpenOption.READ);
        Path del = dir.resolve("deleted.dat");
        if (Files.exists(del)) {
            try (DataInputStream in = open(del)) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    deleted.set(in.readInt());
                }
            }
        }
    }

    private static DataInputStream open(Path p) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(p)));
    }

    public Path dir() {
        return dir;
    }

    public String name() {
        return dir.getFileName().toString();
    }

    public int maxDoc() {
        return urls.length;
    }

    public int liveDocs() {
        return urls.length - deleted.cardinality();
    }

    public boolean isDeleted(int doc) {
        return deleted.get(doc);
    }

    public void markDeleted(int doc) {
        deleted.set(doc);
    }

    /** Persists the deleted set (the only mutable part of a segment), atomically. */
    public void writeDeleted() throws IOException {
        Path tmp = dir.resolve("deleted.dat.tmp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
            out.writeInt(deleted.cardinality());
            for (int d = deleted.nextSetBit(0); d >= 0; d = deleted.nextSetBit(d + 1)) {
                out.writeInt(d);
            }
        }
        Files.move(tmp, dir.resolve("deleted.dat"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public String url(int doc) {
        return urls[doc];
    }

    public String title(int doc) {
        return titles[doc];
    }

    public int length(int doc) {
        return lengths[doc];
    }

    public long simhash(int doc) {
        return simhashes[doc];
    }

    public String text(int doc) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(textLength[doc]);
        textChannel.read(buf, textOffset[doc]);
        return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
    }

    public int termCount() {
        return terms.length;
    }

    public String term(int i) {
        return terms[i];
    }

    private int find(String term) {
        return Arrays.binarySearch(terms, term);
    }

    /** Number of documents containing the term (including deleted ones, like Lucene). */
    public int docFreq(String term) {
        int i = find(term);
        return i < 0 ? 0 : docFreq[i];
    }

    public List<Posting> postings(String term) {
        int i = find(term);
        return i < 0 ? List.of() : postingsAt(i);
    }

    /** Decodes the postings list of the i-th term (gaps → absolute doc ids and positions). */
    public List<Posting> postingsAt(int i) {
        ByteBuffer buf = postings.duplicate();
        buf.position((int) postingsOffset[i]);
        buf.limit((int) (postingsOffset[i] + postingsLength[i]));
        List<Posting> out = new ArrayList<>(docFreq[i]);
        int doc = 0;
        while (buf.hasRemaining()) {
            doc += VarInt.read(buf);
            int tf = VarInt.read(buf);
            int[] pos = new int[VarInt.read(buf)];
            int p = 0;
            for (int k = 0; k < pos.length; k++) {
                p += VarInt.read(buf);
                pos[k] = p;
            }
            out.add(new Posting(doc, tf, pos));
        }
        return out;
    }

    public long sizeOnDisk() throws IOException {
        long total = 0;
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                total += Files.size(p);
            }
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        textChannel.close();
    }
}
