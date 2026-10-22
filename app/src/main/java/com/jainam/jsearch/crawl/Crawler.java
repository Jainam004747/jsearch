package com.jainam.jsearch.crawl;

import com.jainam.jsearch.index.Document;
import com.jainam.jsearch.index.IndexWriter;
import com.jainam.jsearch.text.Html;
import com.jainam.jsearch.text.UrlNormalizer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A polite, concurrent web crawler.
 *
 * <p><b>Politeness</b> (being a good guest on other people's servers):
 * <ul>
 *   <li>obeys robots.txt, including Crawl-delay;</li>
 *   <li>waits at least {@code delay} between requests to the same host, even with many threads;</li>
 *   <li>identifies itself with a User-Agent and honours {@code <meta name="robots">} and rel=nofollow;</li>
 *   <li>stays on the seed sites unless told otherwise, and stops after {@code maxPages}.</li>
 * </ul>
 *
 * <p><b>Concurrency:</b> a fixed pool of worker threads. Each task fetches one URL, indexes it and
 * submits tasks for new links. A counter of unfinished tasks tells when the crawl is complete:
 * it starts at 1 (the seed), +1 for every submitted link, −1 when a task ends. At 0, the
 * frontier is empty and nothing is in flight.
 */
public final class Crawler {
    private static final Logger LOG = Logger.getLogger(Crawler.class.getName());
    public static final String USER_AGENT = "jsearch-bot/1.0 (personal search engine; learning project)";
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    public record Options(int maxPages, int maxDepth, int threads, Duration delay, boolean stayOnSeedHosts) {
        public static Options defaults() {
            return new Options(100, 3, 4, Duration.ofMillis(1000), true);
        }
    }

    public record Stats(int fetched, int indexed, int skippedByRobots, int errors, int duplicates) { }

    /** Per-host state: its robots.txt and the earliest time we may contact it again. */
    private static final class Host {
        RobotsTxt robots;
        long nextAllowedNanos;
    }

    private final Options options;
    private final IndexWriter writer;
    private final HttpClient http;
    private final Consumer<String> progress;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Set<String> seedHosts = ConcurrentHashMap.newKeySet();
    private final Map<String, Host> hosts = new ConcurrentHashMap<>();
    private final AtomicInteger scheduled = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger fetched = new AtomicInteger();
    private final AtomicInteger indexed = new AtomicInteger();
    private final AtomicInteger blocked = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final CountDownLatch done = new CountDownLatch(1);
    private ExecutorService pool;

    public Crawler(IndexWriter writer, Options options, Consumer<String> progress) {
        this.writer = writer;
        this.options = options;
        this.progress = progress;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Stats crawl(List<String> seeds) throws InterruptedException, IOException {
        pool = Executors.newFixedThreadPool(options.threads());
        pending.incrementAndGet(); // guard: keeps the count above 0 while seeds are submitted
        for (String s : seeds) {
            String url = UrlNormalizer.normalize(s);
            seedHosts.add(UrlNormalizer.host(url));
            submit(url, 0);
        }
        finishTask();
        done.await();
        pool.shutdown();
        writer.commit();
        return new Stats(fetched.get(), indexed.get(), blocked.get(), errors.get(), writer.duplicatesSkipped());
    }

    private void submit(String url, int depth) {
        if (depth > options.maxDepth() || !seen.add(url)) {
            return;
        }
        if (options.stayOnSeedHosts() && !seedHosts.contains(UrlNormalizer.host(url))) {
            return;
        }
        if (scheduled.incrementAndGet() > options.maxPages()) {
            return;
        }
        pending.incrementAndGet();
        pool.submit(() -> {
            try {
                visit(url, depth);
            } catch (Exception e) {
                errors.incrementAndGet();
                LOG.fine("failed " + url + ": " + e);
            } finally {
                finishTask();
            }
        });
    }

    private void finishTask() {
        if (pending.decrementAndGet() == 0) {
            done.countDown();
        }
    }

    private void visit(String url, int depth) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        Host host = host(uri);
        if (!host.robots.allowed(pathOf(uri))) {
            blocked.incrementAndGet();
            progress.accept("robots.txt disallows " + url);
            return;
        }
        waitPolitely(host);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        fetched.incrementAndGet();
        String type = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (resp.statusCode() != 200 || !type.contains("html")) {
            progress.accept(resp.statusCode() + " " + url + (type.contains("html") ? "" : " (not HTML, skipped)"));
            return;
        }
        byte[] body = resp.body();
        if (body.length > MAX_BYTES) {
            progress.accept("too large, skipped: " + url);
            return;
        }
        String finalUrl = UrlNormalizer.normalize(resp.uri().toString()); // after redirects
        Html.Page page = Html.parse(new String(body, charsetOf(type)), resp.uri());
        if (!page.noIndex()) {
            IndexWriter.Result r;
            synchronized (writer) {
                r = writer.add(new Document(finalUrl, page.title(), page.text()));
            }
            if (r == IndexWriter.Result.ADDED || r == IndexWriter.Result.REPLACED) {
                indexed.incrementAndGet();
            }
            progress.accept(String.format("[%d] %s %s", indexed.get(), r.name().toLowerCase(Locale.ROOT), finalUrl));
        }
        if (!page.noFollow()) {
            for (String link : page.links()) {
                submit(link, depth + 1);
            }
        }
    }

    /** Fetches and caches robots.txt once per host. A missing robots.txt allows everything. */
    private Host host(URI uri) {
        String key = uri.getScheme() + "://" + uri.getAuthority();
        return hosts.computeIfAbsent(key, k -> {
            Host h = new Host();
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(k + "/robots.txt"))
                        .timeout(Duration.ofSeconds(10)).header("User-Agent", USER_AGENT).build(),
                        HttpResponse.BodyHandlers.ofString());
                h.robots = r.statusCode() == 200 ? RobotsTxt.parse(r.body(), "jsearch-bot") : RobotsTxt.allowAll();
            } catch (IOException e) {
                h.robots = RobotsTxt.allowAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                h.robots = RobotsTxt.allowAll();
            }
            return h;
        });
    }

    /**
     * Reserves the next time slot for this host and sleeps until it arrives. The slot is reserved
     * under the lock, but the sleep happens outside it, so other hosts are not held up.
     */
    private void waitPolitely(Host h) throws InterruptedException {
        long delay = Math.max(options.delay().toNanos(), (long) (h.robots.crawlDelaySeconds() * 1e9));
        long wakeAt;
        synchronized (h) {
            long now = System.nanoTime();
            wakeAt = Math.max(now, h.nextAllowedNanos);
            h.nextAllowedNanos = wakeAt + delay;
        }
        long sleep = wakeAt - System.nanoTime();
        if (sleep > 0) {
            Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
        }
    }

    private static String pathOf(URI uri) {
        String p = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? p : p + "?" + uri.getRawQuery();
    }

    private static Charset charsetOf(String contentType) {
        int i = contentType.indexOf("charset=");
        if (i >= 0) {
            try {
                return Charset.forName(contentType.substring(i + 8).replace("\"", "").trim());
            } catch (Exception ignored) {
                // unknown charset: fall back to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }
}
