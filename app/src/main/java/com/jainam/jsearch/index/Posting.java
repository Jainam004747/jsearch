package com.jainam.jsearch.index;

/**
 * One entry of a postings list: "term X appears in document {@code doc}".
 *
 * @param doc       document id, local to its segment
 * @param tf        weighted term frequency: each body occurrence counts 1, each title occurrence
 *                  counts {@link SegmentWriter#TITLE_WEIGHT}, so title matches rank higher
 * @param positions word positions, for phrase queries ("new york" must be adjacent)
 */
public record Posting(int doc, int tf, int[] positions) { }
