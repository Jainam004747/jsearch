# jsearch Architecture

## Packages

| Package | Classes | Responsibility |
|---|---|---|
| `text` | `Analyzer`, `PorterStemmer`, `Html`, `UrlNormalizer`, `SimHash` | Turning raw pages into terms, links and fingerprints |
| `index` | `IndexWriter`, `SegmentWriter`, `SegmentReader`, `Merger`, `Manifest`, `VarInt` | Building, storing and merging the inverted index |
| `search` | `Query`, `Searcher`, `Snippets`, `Evaluation` | Parsing queries, ranking, snippets, quality metrics |
| `crawl` | `Crawler`, `RobotsTxt`, `FileIndexer` | Getting documents from the web or from disk |
| `web` | `SearchServer`, `Json`, `resources/web/index.html` | HTTP API and UI |

## Analysis pipeline

`"The Cafés were CONNECTING!"` becomes `cafe@1, connect@3`:

1. NFD normalisation and removal of combining marks (é → e), then lowercase.
2. Split on non-letters/digits. Positions count *every* word.
3. Drop stop words. They still used up a position, so phrase offsets stay correct.
4. Porter-stem alphabetic words. Codes like `x86` are kept as they are.

The same analyzer runs at index time and at query time, which is what makes them match.

## Segment format

```
seg_000007/
  terms.dat     int n; n × (UTF term, int df, long postingsOffset, int postingsLength)   sorted by term
  postings.dat  per term: per doc: varint Δdoc, varint tf, varint #pos, varint Δpos...
  docs.dat      int n; n × (url, title, int length, long simhash, long textOffset, int textLength)
  text.dat      concatenated UTF-8 document texts (for snippets)
  deleted.dat   int n; n × int docId            the only file ever rewritten
manifest        next=8 / segment=seg_000007 / ...      replaced atomically on commit
```

- **Term lookup:** binary search over the in-memory sorted term array.
- **Postings:** read from a memory-mapped file and decoded on demand.
- **tf weighting:** title occurrences count 3×. Positions for the body start at 1000, so a phrase cannot span the title and the body.

## Writing and merging

- `IndexWriter.add` buffers documents; every `maxBufferedDocs` documents (and on `commit`) the buffer becomes a new segment.
- **Replacing a URL** marks the old copy in `deleted.dat`. The manifest is saved *before* the deletions, so a crash can leave a page indexed twice but never lose it.
- **Merging (`Merger`):**
  1. Renumber live documents.
  2. K-way merge the sorted term dictionaries with a priority queue.
  3. Concatenate each term's postings with the remapped doc ids, dropping deleted documents.
  4. Write one new segment, then switch the manifest.
- Merging runs automatically when there are more than 8 segments.

## Ranking

```
score(q, d) = Σ_t  idf(t) · tf·(k1+1) / (tf + k1·(1 − b + b·|d|/avgdl))      k1 = 1.2, b = 0.75
idf(t)      = ln(1 + (N − df + 0.5)/(df + 0.5))
```

- All terms are optional (OR semantics); documents matching more terms simply score higher.
- Phrases are hard filters, checked with position offsets, and multiply the score by 1.5.
- `-term` and `site:` are filters.
- A size-k min-heap keeps the best k candidates.

## Crawler

- **Frontier:** tasks on a fixed thread pool.
- **Termination:** a "pending" counter (+1 on submit, −1 on finish); the crawl is complete at 0.
- **Politeness:** per-host state holds robots.txt (fetched once) and `nextAllowedNanos`. A worker reserves the next slot under the host's lock, then sleeps outside the lock, so different hosts proceed in parallel while each host gets one request per `delay` (or `Crawl-delay`).
- **Indexing** is serialised with `synchronized (writer)`; fetching and parsing run in parallel.
