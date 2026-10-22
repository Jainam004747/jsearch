# Benchmarks

`./gradlew run --args="bench 20000"`: 20,000 synthetic pages of 150–600 words, with word frequencies following Zipf's law over a 50,000-word vocabulary. Run on a 2-vCPU container with OpenJDK 21.

| Measure | Result |
|---|---|
| Raw text | 29.5 MB |
| Indexing | ~1,500 docs/s (13 s), including near-duplicate checks |
| Index on disk | 57.6 MB: stored text (~29.5 MB, for snippets) + postings with positions |
| Distinct terms | 49,049 |
| Near-duplicates found | 0 (correct: the pages are random) |

| Query | Matches | p50 | p99 |
|---|---:|---:|---:|
| 1 rare term | 21 | 2.9 ms | 20 ms |
| 1 very common term | 19,973 | 5.1 ms | 29 ms |
| 3 mixed terms | 19,854 | 4.1 ms | 13 ms |
| 2-word phrase | 18,016 | 17 ms | 44 ms |

(Each query includes an extra unique non-matching word so the result cache never answers. p99 includes JIT warm-up.)

## What the numbers taught

- **Common terms cost more.** A term in every page means decoding ~20,000 postings; a rare term decodes a handful. Real engines skip through long lists with skip pointers or block-max indexes (WAND).
- **Phrases are the most expensive query.** Every candidate document needs its position lists checked.
- **Duplicate detection dominates indexing.** The O(n) fingerprint scan per page makes indexing O(n²). At 20,000 pages that is 200 million comparisons; LSH banding would make it near O(1) per page.
- **The first benchmark run found a correctness bug, not a speed problem.** Half the pages were reported as near-duplicates; the cause was a weak hash function ([D-005](DECISIONS.md)).
