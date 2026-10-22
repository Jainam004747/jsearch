package com.jainam.jsearch.crawl;

import com.jainam.jsearch.index.Document;
import com.jainam.jsearch.index.IndexWriter;
import com.jainam.jsearch.text.Html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Indexes files on this computer (notes, docs, saved pages), the "private" in private search
 * engine. Supported: .txt, .md, .html/.htm. The title is the HTML title, the first Markdown
 * heading, or the file name.
 */
public final class FileIndexer {
    private FileIndexer() { }

    public static int indexDirectory(Path root, IndexWriter writer, Consumer<String> progress) throws IOException {
        int count = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).filter(FileIndexer::supported).sorted().toList();
        }
        for (Path f : files) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            String name = f.getFileName().toString();
            String title;
            String text;
            if (name.toLowerCase(Locale.ROOT).endsWith(".html") || name.toLowerCase(Locale.ROOT).endsWith(".htm")) {
                Html.Page page = Html.parse(content, f.toUri());
                title = page.title().isBlank() ? name : page.title();
                text = page.text();
            } else {
                title = name;
                text = content;
                for (String line : content.lines().limit(5).toList()) {
                    if (line.startsWith("# ")) {
                        title = line.substring(2).strip();
                        text = content.replaceFirst("(?m)^# .*$", "").strip(); // the heading is the title
                        break;
                    }
                }
            }
            IndexWriter.Result r = writer.add(new Document(f.toUri().toString(), title, text));
            progress.accept(r.name().toLowerCase(Locale.ROOT) + " " + f);
            if (r == IndexWriter.Result.ADDED || r == IndexWriter.Result.REPLACED) {
                count++;
            }
        }
        writer.commit();
        return count;
    }

    private static boolean supported(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".html") || n.endsWith(".htm");
    }
}
