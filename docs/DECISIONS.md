# Design Decisions

### D-001: Immutable segments plus merging (log-structured)
- **Options:** Update one big index in place; write immutable segments and merge them.
- **Why:** Appending a segment is cheap and crash-safe, readers never see half-written data, and merges reclaim space from deleted documents. This is Lucene's design.
- **Trade-offs:** A query visits every segment, so merging keeps their number small.

### D-002: Delta + varint postings, with positions
- **Why:** Sorted doc ids and positions turn into small gaps, and varints store most gaps in one byte. Positions enable phrase queries.
- **Trade-offs:** Positions are most of the index size; a positions-free index would be several times smaller.

### D-003: BM25 rather than raw TF-IDF
- **Why:** BM25 saturates term frequency and normalises for document length, the two weaknesses of raw TF-IDF. The ranking tests show a short focused page beating a long one with the same counts.

### D-004: One weighted field instead of separate title/body fields
- **Decision:** Title words add 3 to tf, body words add 1, and there is one postings list per term.
- **Why:** Half the index structures of true multi-field BM25F, and most of the benefit.

### D-005: SimHash for near-duplicates, and three mistakes found along the way
1. **3-word shingles:** on short pages one changed word flipped ~15 of 64 bits, so obvious copies were missed. Fixed by switching to word features.
2. **Counting repeated words:** the most frequent words decided every bit, and the benchmark showed half of 20,000 unrelated pages marked as "duplicates". Fixed by giving each distinct word one vote.
3. **Weak hash:** FNV-1a's high bits barely change for short similar strings, so fingerprints of unrelated pages still shared their top bits. Fixed by adding the MurmurHash3 finalizer (`fmix64`).

After the fixes, unrelated random pages are about 27 bits apart (ideal: 32), and a page with one word changed is within 3.

### D-007: Hand-written HTML text extraction
- **Options:** jsoup (a full HTML5 parser); a forgiving single-pass scanner.
- **Why:** For search, only the title, the visible text and the links matter. A small scanner that skips script/style/head and decodes entities covers that without a dependency.
- **Trade-offs:** No DOM, no CSS-hidden-text detection, no JavaScript rendering.

### D-008: Politeness implemented with time-slot reservation
- **Why:** With N threads, a naive "sleep then fetch" lets several threads hit one host at once. Reserving `nextAllowed` under a per-host lock, then sleeping outside it, guarantees the spacing without blocking other hosts. A test measures the gaps between requests.

### D-009: Evaluate ranking with judged queries
- **Why:** Ranking changes cannot be judged by intuition. A judgments file plus P@5, MRR and nDCG@10 gives a number for each change, and a test keeps MRR above 0.9.
