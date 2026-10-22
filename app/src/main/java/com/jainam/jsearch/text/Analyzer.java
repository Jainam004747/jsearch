package com.jainam.jsearch.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns text into index terms. The SAME analyzer must be used for documents and for queries,
 * otherwise a query word would never match its indexed form.
 *
 * <pre>
 *   "The Cafés were CONNECTING!"
 *     1. normalize   → "the cafes were connecting!"   (lowercase, accents removed)
 *     2. tokenize    → [the, cafes, were, connecting]
 *     3. stop words  → [cafes, connecting]           ("the", "were" carry no meaning)
 *     4. stem        → [cafe, connect]
 * </pre>
 *
 * Each token keeps its position (0, 1, 2, ... in the original sequence, counting stop words),
 * so phrase queries can check that words appear next to each other.
 */
public final class Analyzer {

    /** A term and its position in the text. */
    public record Token(String term, int position) { }

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is",
            "it", "no", "not", "of", "on", "or", "such", "that", "the", "their", "then", "there",
            "these", "they", "this", "to", "was", "were", "will", "with", "i", "you", "he", "she",
            "we", "its", "our", "your", "from", "has", "have", "had", "been", "so", "than", "too");

    private static final int MAX_TOKEN_LENGTH = 40;

    private Analyzer() { }

    public static boolean isStopWord(String word) {
        return STOP_WORDS.contains(word);
    }

    /** Lowercases and strips accents: "Café" → "cafe". */
    public static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Splits on anything that is not a letter or digit; positions count every word. */
    public static List<Token> analyze(String text, int startPosition) {
        String norm = normalize(text);
        List<Token> out = new ArrayList<>();
        int pos = startPosition;
        int i = 0;
        int n = norm.length();
        while (i < n) {
            while (i < n && !Character.isLetterOrDigit(norm.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && Character.isLetterOrDigit(norm.charAt(i))) {
                i++;
            }
            if (i > start) {
                String word = norm.substring(start, i);
                if (word.length() <= MAX_TOKEN_LENGTH && !STOP_WORDS.contains(word)) {
                    out.add(new Token(stemWord(word), pos));
                }
                pos++;
            }
        }
        return out;
    }

    public static List<Token> analyze(String text) {
        return analyze(text, 0);
    }

    /** Stems alphabetic words only; numbers and codes like "x86" are kept as they are. */
    public static String stemWord(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'a' || c > 'z') {
                return word;
            }
        }
        return PorterStemmer.stem(word);
    }

    /** Analyzes a single query word (or returns null if it is a stop word). */
    public static String term(String word) {
        List<Token> t = analyze(word);
        return t.isEmpty() ? null : t.get(0).term();
    }
}
