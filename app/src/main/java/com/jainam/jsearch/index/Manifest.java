package com.jainam.jsearch.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of segments that make up the index right now.
 *
 * <p>Committing new data = writing new segment directories, then replacing this one small file.
 * The replacement is an atomic rename, so a reader (or a crash) sees either the old list or the
 * new one, never a mix. Segment directories not listed here are leftovers and can be deleted.
 */
public record Manifest(List<String> segments, int nextSegment) {

    static final String FILE = "manifest";

    public static Manifest load(Path dir) throws IOException {
        Path f = dir.resolve(FILE);
        if (!Files.exists(f)) {
            return new Manifest(List.of(), 1);
        }
        List<String> segs = new ArrayList<>();
        int next = 1;
        for (String line : Files.readAllLines(f)) {
            if (line.startsWith("next=")) {
                next = Integer.parseInt(line.substring(5).trim());
            } else if (line.startsWith("segment=")) {
                segs.add(line.substring(8).trim());
            }
        }
        return new Manifest(List.copyOf(segs), next);
    }

    public void save(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder("# jsearch index manifest\n");
        sb.append("next=").append(nextSegment).append('\n');
        for (String s : segments) {
            sb.append("segment=").append(s).append('\n');
        }
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.writeString(tmp, sb.toString());
        Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
