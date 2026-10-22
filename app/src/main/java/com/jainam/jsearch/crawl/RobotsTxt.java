package com.jainam.jsearch.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses robots.txt, the file where a site says which paths crawlers may visit.
 *
 * <pre>
 *   User-agent: *
 *   Disallow: /private/
 *   Allow: /private/press/
 *   Crawl-delay: 2
 * </pre>
 *
 * Rules: use the group for our user agent if there is one, otherwise the "*" group. The rule
 * with the LONGEST matching path wins, and Allow wins a tie (RFC 9309). "*" in a path matches
 * anything, and "$" anchors the end.
 */
public final class RobotsTxt {
    private record Rule(String path, boolean allow) { }

    private final List<Rule> rules;
    private final double crawlDelaySeconds;

    private RobotsTxt(List<Rule> rules, double crawlDelaySeconds) {
        this.rules = rules;
        this.crawlDelaySeconds = crawlDelaySeconds;
    }

    public static RobotsTxt allowAll() {
        return new RobotsTxt(List.of(), 0);
    }

    public static RobotsTxt parse(String content, String userAgent) {
        String agent = userAgent.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> generic = new ArrayList<>();
        double delaySpecific = -1;
        double delayGeneric = -1;
        List<String> currentAgents = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String raw : content.split("\r?\n")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                if (!lastWasAgent) {
                    currentAgents.clear(); // a new group starts
                }
                currentAgents.add(value.toLowerCase(Locale.ROOT));
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            boolean forUs = currentAgents.stream().anyMatch(a -> !a.equals("*") && agent.contains(a));
            boolean forAll = currentAgents.contains("*");
            if (!forUs && !forAll) {
                continue;
            }
            switch (field) {
                case "disallow", "allow" -> {
                    if (value.isEmpty()) {
                        continue; // "Disallow:" with no path allows everything
                    }
                    Rule r = new Rule(value, field.equals("allow"));
                    (forUs ? specific : generic).add(r);
                }
                case "crawl-delay" -> {
                    try {
                        double d = Double.parseDouble(value);
                        if (forUs) {
                            delaySpecific = d;
                        } else {
                            delayGeneric = d;
                        }
                    } catch (NumberFormatException ignored) {
                        // malformed value: ignore it
                    }
                }
                default -> { }
            }
        }
        boolean useSpecific = !specific.isEmpty() || delaySpecific >= 0;
        return new RobotsTxt(useSpecific ? specific : generic,
                Math.max(0, useSpecific ? delaySpecific : delayGeneric));
    }

    public boolean allowed(String path) {
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        Rule best = null;
        for (Rule r : rules) {
            if (matches(r.path(), path)) {
                if (best == null || r.path().length() > best.path().length()
                        || (r.path().length() == best.path().length() && r.allow())) {
                    best = r;
                }
            }
        }
        return best == null || best.allow();
    }

    public double crawlDelaySeconds() {
        return crawlDelaySeconds;
    }

    /** Pattern match with '*' (any characters) and a trailing '$' (end of path). */
    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String p = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        return match(p, 0, path, 0, anchored);
    }

    private static boolean match(String p, int pi, String s, int si, boolean anchored) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                for (int k = si; k <= s.length(); k++) {
                    if (match(p, pi + 1, s, k, anchored)) {
                        return true;
                    }
                }
                return false;
            }
            if (si >= s.length() || s.charAt(si) != c) {
                return false;
            }
            pi++;
            si++;
        }
        return !anchored || si == s.length();
    }
}
