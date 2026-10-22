package com.jainam.jsearch.index;

/**
 * One searchable document: a web page or a local file.
 *
 * @param url     unique identity (re-indexing the same URL replaces the old copy)
 * @param title   shown in results; its words count extra in ranking
 * @param text    the visible text, used for indexing and for snippets
 */
public record Document(String url, String title, String text) {
    /** Stored text is capped so one huge page cannot bloat the index. */
    public static final int MAX_STORED_TEXT = 100_000;

    public Document {
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        if (text.length() > MAX_STORED_TEXT) {
            text = text.substring(0, MAX_STORED_TEXT);
        }
    }
}
