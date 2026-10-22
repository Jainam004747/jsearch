package com.jainam.jsearch.search;

import com.jainam.jsearch.text.Analyzer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the short excerpt shown under each result, with the query words in bold.
 *
 * <p>The best excerpt is the window of {@link #WINDOW} words that contains the most DIFFERENT
 * query terms (ties go to more total matches). A sliding window finds it in one pass over the
 * document's words.
 */
public final class Snippets {
    static final int WINDOW = 30;

    private Snippets() { }

    private record Word(int start, int end, String term) { }

    public static String make(String text, List<String> queryTerms) {
        List<Word> words = words(text);
        if (words.isEmpty()) {
            return "";
        }
        Set<String> wanted = new HashSet<>(queryTerms);
        int bestStart = 0;
        int bestDistinct = -1;
        int bestTotal = -1;
        for (int start = 0; start < words.size(); start++) {
            if (start > 0 && !wanted.contains(words.get(start).term())) {
                continue; // a best window can always start on a match (or at the beginning)
            }
            Set<String> seen = new HashSet<>();
            int total = 0;
            for (int i = start; i < Math.min(words.size(), start + WINDOW); i++) {
                String t = words.get(i).term();
                if (t != null && wanted.contains(t)) {
                    seen.add(t);
                    total++;
                }
            }
            if (seen.size() > bestDistinct || (seen.size() == bestDistinct && total > bestTotal)) {
                bestDistinct = seen.size();
                bestTotal = total;
                bestStart = start;
            }
        }
        // Start a few words before the first match, for context.
        int from = Math.max(0, bestStart - 4);
        int to = Math.min(words.size(), from + WINDOW);
        StringBuilder sb = new StringBuilder();
        if (from > 0) {
            sb.append("… ");
        }
        int cursor = words.get(from).start();
        for (int i = from; i < to; i++) {
            Word w = words.get(i);
            sb.append(escape(text.substring(cursor, w.start()).replaceAll("\\s+", " ")));
            String original = escape(text.substring(w.start(), w.end()));
            if (w.term() != null && wanted.contains(w.term())) {
                sb.append("<b>").append(original).append("</b>");
            } else {
                sb.append(original);
            }
            cursor = w.end();
        }
        if (to < words.size()) {
            sb.append(" …");
        }
        return sb.toString().strip();
    }

    /** Splits text into words, remembering where each one is, and analyzes it. */
    private static List<Word> words(String text) {
        List<Word> out = new ArrayList<>();
        int i = 0;
        int n = Math.min(text.length(), 50_000);
        while (i < n) {
            while (i < n && !Character.isLetterOrDigit(text.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && Character.isLetterOrDigit(text.charAt(i))) {
                i++;
            }
            if (i > start) {
                String w = Analyzer.normalize(text.substring(start, i));
                String term = Analyzer.isStopWord(w) ? null : Analyzer.stemWord(w.toLowerCase(Locale.ROOT));
                out.add(new Word(start, i, term));
            }
        }
        return out;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
