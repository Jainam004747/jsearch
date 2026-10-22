# Development Log

## 2026-10-21 - Building jsearch

### Setup
The Gradle project was created the same way as jredis (Gradle wrapper, Java 25 toolchain, JUnit 5, GitHub Actions). There are no runtime dependencies: HTTP uses `java.net.http.HttpClient`, and the server uses the JDK's `com.sun.net.httpserver`.

### Build order
1. `text/`: Porter stemmer (tested against the paper's examples), analyzer, HTML extraction, URL normalisation, SimHash.
2. `index/`: varints, segment writer/reader, manifest, k-way merger, index writer with replace/delete.
3. `search/`: query language, BM25 searcher with phrase positions, snippets, evaluation metrics.
4. `crawl/`: robots.txt, polite concurrent crawler, local folder indexer.
5. `web/`: JSON API and UI.
6. A sample corpus (25 pages) with relevance judgments, tests, and a benchmark.

### Findings
- **Phrase queries with stop words failed.** `"tea is brewed"` did not match because "is" is not indexed but still takes a position. Phrases now keep each term's offset.
- **Mirror page not detected.** The Markdown heading was part of the body, and the copy's heading differed. The heading is now used as the title and removed from the body.
- **SimHash, three iterations.** See DECISIONS.md D-005. The benchmark exposed the third problem.
- **Query-cache trap in the benchmark.** The first benchmark varied the query text in only two ways, so most "queries" were cache hits at 0.00 ms. Each query now gets a unique extra word.
