package com.jainam.jsearch;

import com.jainam.jsearch.crawl.Crawler;
import com.jainam.jsearch.crawl.FileIndexer;
import com.jainam.jsearch.index.IndexWriter;
import com.jainam.jsearch.search.Evaluation;
import com.jainam.jsearch.search.Searcher;
import com.jainam.jsearch.web.SearchServer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point.
 *
 * <pre>
 *   crawl &lt;url&gt;... [--max 100] [--depth 3] [--threads 4] [--delay-ms 1000] [--any-host]
 *   index-dir &lt;folder&gt;            index local .txt / .md / .html files
 *   search "query" [--limit 10]
 *   serve [--port 8081]             web UI at http://localhost:8081
 *   merge                           merge all segments into one
 *   eval &lt;judgments.tsv&gt;            precision@5, MRR, nDCG@10
 *   stats
 *   (every command accepts --index &lt;dir&gt;, default ./index)
 * </pre>
 */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        // Print UTF-8 regardless of the terminal's default encoding (snippets contain "…" etc.).
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            return;
        }
        String cmd = args[0];
        Path index = Path.of("index");
        List<String> positional = new ArrayList<>();
        int max = 100, depth = 3, threads = 4, delayMs = 1000, limit = 10, port = 8081;
        boolean anyHost = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--index" -> index = Path.of(args[++i]);
                case "--max" -> max = Integer.parseInt(args[++i]);
                case "--depth" -> depth = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--delay-ms" -> delayMs = Integer.parseInt(args[++i]);
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--any-host" -> anyHost = true;
                default -> positional.add(args[i]);
            }
        }
        switch (cmd) {
            case "crawl" -> {
                if (positional.isEmpty()) {
                    throw new IllegalArgumentException("crawl needs at least one seed URL");
                }
                try (IndexWriter w = new IndexWriter(index)) {
                    Crawler c = new Crawler(w, new Crawler.Options(max, depth, threads, Duration.ofMillis(delayMs), !anyHost),
                            System.out::println);
                    Crawler.Stats s = c.crawl(positional);
                    System.out.printf("%nfetched %d, indexed %d, blocked by robots.txt %d, near-duplicates %d, errors %d%n",
                            s.fetched(), s.indexed(), s.skippedByRobots(), s.duplicates(), s.errors());
                }
            }
            case "index-dir" -> {
                try (IndexWriter w = new IndexWriter(index)) {
                    int n = FileIndexer.indexDirectory(Path.of(positional.get(0)), w, System.out::println);
                    System.out.printf("indexed %d files, %d near-duplicates skipped%n", n, w.duplicatesSkipped());
                }
            }
            case "search" -> {
                try (Searcher s = new Searcher(index)) {
                    Searcher.Results r = s.search(String.join(" ", positional), 0, limit);
                    System.out.printf("%d results (%.1f ms)%n%n", r.totalMatches(), r.millis());
                    int rank = 1;
                    for (Searcher.Hit h : r.hits()) {
                        System.out.printf(Locale.ROOT, "%2d. %s  [%.2f]%n    %s%n    %s%n%n", rank++, h.title(), h.score(),
                                h.url(), h.snippet().replace("<b>", "\u001b[1m").replace("</b>", "\u001b[0m")
                                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""));
                    }
                }
            }
            case "serve" -> {
                SearchServer server = new SearchServer(index);
                server.start("127.0.0.1", port);
                System.out.println("jsearch: open http://localhost:" + server.port());
            }
            case "merge" -> {
                try (IndexWriter w = new IndexWriter(index)) {
                    w.mergeAll();
                    System.out.println("merged into " + w.segmentCount() + " segment");
                }
            }
            case "eval" -> {
                try (Searcher s = new Searcher(index)) {
                    Evaluation.Report r = Evaluation.run(s, Path.of(positional.get(0)));
                    for (Evaluation.QueryResult q : r.queries()) {
                        System.out.printf(Locale.ROOT, "%-40s P@5 %.2f  RR %.2f  nDCG@10 %.2f%n", q.query(), q.precisionAt5(),
                                q.reciprocalRank(), q.ndcgAt10());
                    }
                    System.out.printf(Locale.ROOT, "%nmean: P@5 %.3f  MRR %.3f  nDCG@10 %.3f  (%d queries)%n", r.precisionAt5(),
                            r.mrr(), r.ndcgAt10(), r.queries().size());
                }
            }
            case "bench" -> Bench.run(positional.isEmpty() ? 20_000 : Integer.parseInt(positional.get(0)));
            case "stats" -> {
                try (Searcher s = new Searcher(index)) {
                    System.out.printf("documents %d, terms %d, segments %d, %d KB on disk%n", s.totalDocs(), s.termCount(),
                            s.segmentCount(), s.sizeOnDisk() / 1024);
                }
            }
            default -> {
                System.err.println("unknown command: " + cmd);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                jsearch: a private search engine built from scratch

                  crawl <url>... [--max 100] [--depth 3] [--threads 4] [--delay-ms 1000] [--any-host]
                  index-dir <folder>
                  search "query" [--limit 10]
                  serve [--port 8081]
                  merge
                  eval <judgments.tsv>
                  stats
                  bench [documents]
                  (all commands accept --index <dir>; default ./index)""");
    }
}
