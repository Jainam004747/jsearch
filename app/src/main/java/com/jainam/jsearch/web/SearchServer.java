package com.jainam.jsearch.web;

import com.jainam.jsearch.search.Searcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP front end: a JSON search API and a small single-page UI.
 *
 * <pre>
 *   GET /                          the search page
 *   GET /api/search?q=...&amp;page=1   {"query","total","millis","cached","results":[{url,title,snippet,score}]}
 *   GET /api/stats                 index size, documents, terms, segments
 * </pre>
 *
 * Binds to 127.0.0.1 by default: this is a private search engine.
 */
public final class SearchServer {
    private static final int PAGE_SIZE = 10;
    private final Searcher searcher;
    private HttpServer http;

    public SearchServer(Path indexDir) throws IOException {
        this.searcher = new Searcher(indexDir);
    }

    public void start(String bind, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.createContext("/", this::page);
        http.createContext("/api/search", this::search);
        http.createContext("/api/stats", this::stats);
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() throws IOException {
        http.stop(0);
        searcher.close();
    }

    private void search(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String text = q.getOrDefault("q", "");
        int page = 1;
        try {
            page = Math.max(1, Integer.parseInt(q.getOrDefault("page", "1")));
        } catch (NumberFormatException ignored) {
            // keep page 1
        }
        Searcher.Results r = searcher.search(text, (page - 1) * PAGE_SIZE, PAGE_SIZE);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Searcher.Hit h : r.hits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", h.url());
            m.put("title", h.title());
            m.put("snippet", h.snippet());
            m.put("score", Math.round(h.score() * 1000) / 1000.0);
            hits.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", text);
        body.put("total", r.totalMatches());
        body.put("page", page);
        body.put("millis", Math.round(r.millis() * 100) / 100.0);
        body.put("cached", r.cached());
        body.put("results", hits);
        json(ex, body);
    }

    private void stats(HttpExchange ex) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documents", searcher.totalDocs());
        body.put("terms", searcher.termCount());
        body.put("segments", searcher.segmentCount());
        body.put("bytes", searcher.sizeOnDisk());
        json(ex, body);
    }

    private void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] html;
        try (InputStream in = SearchServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                throw new IOException("web/index.html missing from the build");
            }
            html = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(html);
        }
    }

    private static void json(HttpExchange ex, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }
}
