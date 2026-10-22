package com.jainam.jsearch.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Measures search quality against human judgments, so ranking changes can be compared with
 * numbers instead of impressions.
 *
 * <p>A judgments file has one query per line: {@code query<TAB>relevant-url<TAB>relevant-url...}
 *
 * <ul>
 *   <li><b>Precision@5</b>: what fraction of the top 5 results are relevant.</li>
 *   <li><b>MRR</b> (mean reciprocal rank): 1 / rank of the first relevant result (1st = 1.0,
 *       2nd = 0.5, ...), averaged over queries. Measures "is the answer at the top?".</li>
 *   <li><b>nDCG@10</b>: rewards relevant results more the higher they are ranked
 *       (gain 1 / log2(rank + 1)), divided by the best possible ordering.</li>
 * </ul>
 */
public final class Evaluation {
    public record QueryResult(String query, double precisionAt5, double reciprocalRank, double ndcgAt10) { }

    public record Report(List<QueryResult> queries, double precisionAt5, double mrr, double ndcgAt10) { }

    private Evaluation() { }

    public static Report run(Searcher searcher, Path judgments) throws IOException {
        List<QueryResult> results = new ArrayList<>();
        for (String line : Files.readAllLines(judgments)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            Set<String> relevant = new HashSet<>();
            for (int i = 1; i < parts.length; i++) {
                relevant.add(parts[i].trim());
            }
            List<Searcher.Hit> hits = searcher.search(parts[0], 0, 10).hits();
            results.add(score(parts[0], hits, relevant));
        }
        double p5 = results.stream().mapToDouble(QueryResult::precisionAt5).average().orElse(0);
        double mrr = results.stream().mapToDouble(QueryResult::reciprocalRank).average().orElse(0);
        double ndcg = results.stream().mapToDouble(QueryResult::ndcgAt10).average().orElse(0);
        return new Report(results, p5, mrr, ndcg);
    }

    static QueryResult score(String query, List<Searcher.Hit> hits, Set<String> relevant) {
        int relevantInTop5 = 0;
        double rr = 0;
        double dcg = 0;
        for (int i = 0; i < hits.size() && i < 10; i++) {
            String url = hits.get(i).url();
            boolean rel = relevant.stream().anyMatch(url::endsWith);
            if (rel) {
                if (i < 5) {
                    relevantInTop5++;
                }
                if (rr == 0) {
                    rr = 1.0 / (i + 1);
                }
                dcg += 1 / log2(i + 2);
            }
        }
        double ideal = 0;
        for (int i = 0; i < Math.min(10, relevant.size()); i++) {
            ideal += 1 / log2(i + 2);
        }
        return new QueryResult(query, relevantInTop5 / 5.0, rr, ideal == 0 ? 0 : dcg / ideal);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
