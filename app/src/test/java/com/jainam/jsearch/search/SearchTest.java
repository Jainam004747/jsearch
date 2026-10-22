package com.jainam.jsearch.search;

import com.jainam.jsearch.index.Document;
import com.jainam.jsearch.index.IndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTest {
    @TempDir
    Path dir;

    private void index(int bufferSize, Document... docs) throws IOException {
        try (IndexWriter w = new IndexWriter(dir, bufferSize)) {
            for (Document d : docs) {
                w.add(d);
            }
        }
    }

    private List<String> urls(String query) throws IOException {
        try (Searcher s = new Searcher(dir)) {
            return s.search(query, 0, 10).hits().stream().map(Searcher.Hit::url).toList();
        }
    }

    private static final Document ESPRESSO = new Document("u:espresso", "Espresso",
            "Espresso is brewed by forcing hot water through fine coffee grounds under pressure.");
    private static final Document PRESS = new Document("u:press", "French press",
            "The French press steeps coarse coffee in hot water, then a mesh plunger separates the grounds.");
    private static final Document TEA = new Document("u:tea", "Tea",
            "Green tea prefers cooler water. Black tea is brewed with boiling water.");
    private static final Document NEWS = new Document("http://news.example.com/x", "Press release",
            "The press office announced new opening hours for the cafe.");

    @Test
    void rankingPrefersRareTermsAndTitles() throws IOException {
        index(100, ESPRESSO, PRESS, TEA, NEWS);
        assertEquals("u:espresso", urls("espresso").get(0));
        assertEquals("u:tea", urls("tea water").get(0)); // "tea" is rarer than "water"
        // Both have "press" in the title and once in the body; the shorter page ranks first (BM25 length norm).
        assertEquals(List.of("http://news.example.com/x", "u:press"), urls("press"));
    }

    @Test
    void phrasesExclusionsAndSiteFilter() throws IOException {
        index(100, ESPRESSO, PRESS, TEA, NEWS);
        assertEquals(List.of("u:press"), urls("\"french press\""));
        assertEquals(List.of(), urls("\"press french\""));                 // wrong order
        assertEquals(List.of("u:tea"), urls("\"tea is brewed\""));         // stop word gap respected
        assertEquals(List.of("u:press"), urls("press -announced"));
        assertEquals(List.of("http://news.example.com/x"), urls("press site:example.com"));
    }

    @Test
    void resultsAreTheSameBeforeAndAfterMergingSegments() throws IOException {
        // Buffer of 1 document: every add creates a new segment.
        index(1, ESPRESSO, PRESS, TEA, NEWS);
        List<String> before = urls("water coffee press");
        try (IndexWriter w = new IndexWriter(dir, 1)) {
            w.mergeAll();
            assertEquals(1, w.segmentCount());
        }
        assertEquals(before, urls("water coffee press"));
    }

    @Test
    void reindexingAUrlReplacesTheOldVersion() throws IOException {
        index(1, ESPRESSO, TEA);
        index(1, new Document("u:espresso", "Espresso", "Now this page is about ristretto shots only."));
        assertEquals(List.of(), urls("pressure"));
        assertEquals(List.of("u:espresso"), urls("ristretto"));
        try (IndexWriter w = new IndexWriter(dir, 1)) {
            w.mergeAll(); // the merge must drop the deleted old copy for good
        }
        assertEquals(List.of("u:espresso"), urls("ristretto"));
        assertEquals(List.of(), urls("pressure"));
    }

    @Test
    void snippetsHighlightMatchesAndEscapeHtml() {
        String s = Snippets.make("Intro words. The <script> tag & cold brew coffee is smooth.", List.of("cold", "brew"));
        assertTrue(s.contains("<b>cold</b> <b>brew</b>"), s);
        assertTrue(s.contains("&lt;script&gt;"), s);
    }

    @Test
    void sampleCorpusQualityStaysHigh() throws IOException {
        Path corpus = Path.of("..", "sample-corpus");
        if (!Files.isDirectory(corpus)) {
            corpus = Path.of("sample-corpus");
        }
        try (IndexWriter w = new IndexWriter(dir)) {
            com.jainam.jsearch.crawl.FileIndexer.indexDirectory(corpus, w, s -> { });
            assertEquals(1, w.duplicatesSkipped(), "the mirror copy of espresso.md is a near-duplicate");
        }
        Path judgments = corpus.resolveSibling("sample-judgments.tsv");
        try (Searcher s = new Searcher(dir)) {
            Evaluation.Report r = Evaluation.run(s, judgments);
            assertTrue(r.mrr() > 0.9, "MRR dropped to " + r.mrr());
        }
    }
}
