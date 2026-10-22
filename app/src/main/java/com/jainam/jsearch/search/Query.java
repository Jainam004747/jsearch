package com.jainam.jsearch.search;

import com.jainam.jsearch.text.Analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parsed search query.
 *
 * <pre>
 *   espresso machine                any of the words; documents with more of them rank higher
 *   "french press"                  the exact phrase (words adjacent, in order)
 *   coffee -decaf                   must NOT contain "decaf"
 *   site:example.com brewing        only results from that host (and its subdomains)
 * </pre>
 *
 * @param terms    analyzed (stemmed) optional terms, scored with BM25
 * @param phrases  phrases whose terms must appear at the same relative positions as in the query
 * @param excluded analyzed terms that must not appear
 * @param site     host filter, or null
 */
public record Query(List<String> terms, List<Phrase> phrases, List<String> excluded, String site) {

    /**
     * A phrase's terms and their offsets from the first term. Stop words are not indexed but still
     * take up a position, so "tea is brewed" is tea at +0 and brew at +2.
     */
    public record Phrase(List<String> terms, int[] offsets) { }

    public boolean isEmpty() {
        return terms.isEmpty() && phrases.isEmpty();
    }

    /** Every term that contributes to the score (plain terms plus phrase words). */
    public List<String> scoringTerms() {
        List<String> all = new ArrayList<>(terms);
        for (Phrase p : phrases) {
            for (String t : p.terms()) {
                if (!all.contains(t)) {
                    all.add(t);
                }
            }
        }
        return all;
    }

    public static Query parse(String input) {
        List<String> terms = new ArrayList<>();
        List<Phrase> phrases = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        String site = null;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int end = input.indexOf('"', i + 1);
                if (end < 0) {
                    end = input.length();
                }
                List<Analyzer.Token> tokens = Analyzer.analyze(input.substring(i + 1, end));
                if (tokens.size() == 1) {
                    terms.add(tokens.get(0).term());
                } else if (!tokens.isEmpty()) {
                    int first = tokens.get(0).position();
                    phrases.add(new Phrase(tokens.stream().map(Analyzer.Token::term).toList(),
                            tokens.stream().mapToInt(t -> t.position() - first).toArray()));
                }
                i = end + 1;
                continue;
            }
            int end = i;
            while (end < input.length() && !Character.isWhitespace(input.charAt(end))) {
                end++;
            }
            String word = input.substring(i, end);
            i = end;
            if (word.toLowerCase(Locale.ROOT).startsWith("site:") && word.length() > 5) {
                site = word.substring(5).toLowerCase(Locale.ROOT);
            } else if (word.startsWith("-") && word.length() > 1) {
                for (Analyzer.Token t : Analyzer.analyze(word.substring(1))) {
                    excluded.add(t.term());
                }
            } else {
                for (Analyzer.Token t : Analyzer.analyze(word)) {
                    if (!terms.contains(t.term())) {
                        terms.add(t.term());
                    }
                }
            }
        }
        return new Query(terms, phrases, excluded, site);
    }
}
