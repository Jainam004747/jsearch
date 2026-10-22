package com.jainam.jsearch.crawl;

import com.jainam.jsearch.index.IndexWriter;
import com.jainam.jsearch.search.Searcher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crawls a small fake website served from this test, so no internet access is needed. */
class CrawlerTest {
    @TempDir
    Path dir;

    private static final Map<String, String> SITE = Map.of(
            "/robots.txt", "User-agent: *\nDisallow: /private/\n",
            "/", "<html><title>Home</title><body>Welcome to the cafe. <a href='/menu'>menu</a> "
                    + "<a href='/private/secret'>secret</a> <a href='/hidden'>hidden</a></body></html>",
            "/menu", "<html><title>Menu</title><body>Espresso, latte and cold brew. <a href='/'>home</a>"
                    + " <a href='/menu-copy'>copy</a></body></html>",
            "/menu-copy", "<html><title>Menu (copy)</title><body>Espresso, latte and cold brew. <a href='/'>home</a>"
                    + " <a href='/menu-copy'>copy</a></body></html>",
            "/private/secret", "<html><body>staff only</body></html>",
            "/hidden", "<html><head><meta name='robots' content='noindex'></head><body>do not index me</body></html>");

    @Test
    void crawlsPolitelyAndRespectsRobots() throws Exception {
        List<Long> requestTimes = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (!path.equals("/robots.txt")) {
                requestTimes.add(System.nanoTime());
            }
            String body = SITE.get(path);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", path.endsWith(".txt") ? "text/plain" : "text/html; charset=utf-8");
            ex.sendResponseHeaders(body == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Crawler.Stats stats;
        try (IndexWriter w = new IndexWriter(dir)) {
            Crawler c = new Crawler(w, new Crawler.Options(50, 3, 4, Duration.ofMillis(150), true), s -> { });
            stats = c.crawl(List.of(base + "/"));
        } finally {
            server.stop(0);
        }
        assertEquals(1, stats.skippedByRobots(), "/private/secret must not be fetched");
        assertEquals(2, stats.indexed(), "home and menu; the copy is a near-duplicate, /hidden is noindex");
        assertEquals(1, stats.duplicates());
        // Politeness: consecutive requests to the same host are at least ~150 ms apart.
        List<Long> sorted = requestTimes.stream().sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            long gapMs = (sorted.get(i) - sorted.get(i - 1)) / 1_000_000;
            assertTrue(gapMs >= 120, "requests only " + gapMs + " ms apart");
        }
        try (Searcher s = new Searcher(dir)) {
            assertEquals(base + "/menu", s.search("latte", 0, 5).hits().get(0).url());
            assertEquals(0, s.search("staff", 0, 5).totalMatches());
        }
    }
}
