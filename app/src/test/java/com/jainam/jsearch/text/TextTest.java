package com.jainam.jsearch.text;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {

    @Test
    void porterStemmerMatchesTheReferenceOutputs() {
        // Pairs from Porter's paper and the reference vocabulary.
        Map<String, String> cases = Map.ofEntries(
                Map.entry("caresses", "caress"), Map.entry("ponies", "poni"), Map.entry("ties", "ti"),
                Map.entry("cats", "cat"), Map.entry("agreed", "agre"), Map.entry("plastered", "plaster"),
                Map.entry("motoring", "motor"), Map.entry("sing", "sing"), Map.entry("conflated", "conflat"),
                Map.entry("troubled", "troubl"), Map.entry("sized", "size"), Map.entry("hopping", "hop"),
                Map.entry("tanned", "tan"), Map.entry("falling", "fall"), Map.entry("hissing", "hiss"),
                Map.entry("fizzed", "fizz"), Map.entry("failing", "fail"), Map.entry("filing", "file"),
                Map.entry("happy", "happi"), Map.entry("sky", "sky"), Map.entry("relational", "relat"),
                Map.entry("conditional", "condit"), Map.entry("rational", "ration"),
                Map.entry("generalizations", "gener"), Map.entry("oscillators", "oscil"),
                Map.entry("connection", "connect"), Map.entry("connecting", "connect"),
                Map.entry("connected", "connect"), Map.entry("connections", "connect"),
                Map.entry("adjustment", "adjust"), Map.entry("probate", "probat"), Map.entry("rate", "rate"),
                Map.entry("cease", "ceas"), Map.entry("controlling", "control"), Map.entry("rolling", "roll"),
                Map.entry("electrical", "electr"), Map.entry("hopeful", "hope"), Map.entry("goodness", "good"),
                Map.entry("revival", "reviv"), Map.entry("allowance", "allow"), Map.entry("inference", "infer"),
                Map.entry("airliner", "airlin"), Map.entry("effective", "effect"), Map.entry("communism", "commun"));
        cases.forEach((word, stem) -> assertEquals(stem, PorterStemmer.stem(word), word));
    }

    @Test
    void analyzerNormalizesStemsAndKeepsPositions() {
        List<Analyzer.Token> t = Analyzer.analyze("The Cafés were CONNECTING to x86 servers!");
        assertEquals(List.of("cafe", "connect", "x86", "server"), t.stream().map(Analyzer.Token::term).toList());
        // Positions count stop words too, which keeps phrase matching honest.
        assertEquals(List.of(1, 3, 5, 6), t.stream().map(Analyzer.Token::position).toList());
    }

    @Test
    void htmlExtractionSkipsScriptsAndFindsLinks() {
        String html = """
                <html><head><title>Coffee &amp; Tea</title><meta name="robots" content="nofollow">
                <script>var x = "<a href='/fake'>";</script><style>p{color:red}</style></head>
                <body><h1>Menu</h1><p>Espresso&nbsp;is &#8220;strong&#8221;.</p>
                <a href="/drinks/latte?size=l#top">Latte</a> <a href="mailto:x@y.z">mail</a>
                <a href='https://Other.COM:443/a/../b'>other</a> <!-- <a href="/hidden"> -->
                </body></html>""";
        Html.Page p = Html.parse(html, URI.create("http://cafe.test/index.html"));
        assertEquals("Coffee & Tea", p.title());
        assertTrue(p.text().contains("Espresso is “strong”."), p.text());
        assertFalse(p.text().contains("var x"));
        assertEquals(List.of("http://cafe.test/drinks/latte?size=l", "https://other.com/b"), p.links());
        assertTrue(p.noFollow());
        assertFalse(p.noIndex());
    }

    @Test
    void urlNormalization() {
        assertEquals("http://example.com/b/", UrlNormalizer.normalize("HTTP://Example.com:80/a/../b/#top"));
        assertEquals("https://example.com/", UrlNormalizer.normalize("https://EXAMPLE.com"));
        assertEquals("http://example.com:8080/x?q=1", UrlNormalizer.normalize("http://example.com:8080/x?q=1"));
    }

    @Test
    void simHashFindsNearDuplicatesButNotDifferentPages() {
        String a = "Redis is an in-memory data store used as a cache, message broker and database. "
                + "Because data lives in RAM, reads and writes typically complete in well under a millisecond. "
                + "Values can be strings, lists, hashes, sets and sorted sets, and any key can be given a time "
                + "to live after which it expires automatically. Redis processes commands on a single thread "
                + "using an event loop, which avoids locking. For durability it offers snapshots and an "
                + "append-only file that logs every write. Clients speak the RESP protocol over TCP.";
        String b = a.replace("typically", "usually"); // one word changed
        String c = "Green tea is heated soon after picking to stop oxidation, so it stays green and grassy.";
        assertTrue(SimHash.nearDuplicate(SimHash.of(a), SimHash.of(b)), "distance " + SimHash.distance(SimHash.of(a), SimHash.of(b)));
        assertFalse(SimHash.nearDuplicate(SimHash.of(a), SimHash.of(c)), "distance " + SimHash.distance(SimHash.of(a), SimHash.of(c)));
    }
}
