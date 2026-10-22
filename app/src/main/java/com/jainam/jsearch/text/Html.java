package com.jainam.jsearch.text;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, forgiving HTML reader: extracts the title, the visible text and the links.
 *
 * <p>Real-world HTML is often invalid, so this is not a strict parser. It scans tags with a
 * single pass, ignores the contents of {@code <script>}, {@code <style>} and similar elements,
 * turns block-level tags into line breaks, and decodes common character entities.
 */
public final class Html {

    /** What an HTML page contributes to the index. */
    public record Page(String title, String text, List<String> links, boolean noIndex, boolean noFollow) { }

    private static final List<String> SKIP = List.of("script", "style", "noscript", "template", "svg", "head");
    private static final List<String> BLOCK = List.of("p", "div", "br", "li", "ul", "ol", "tr", "table", "section",
            "article", "header", "footer", "nav", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "hr");
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("copy", "©"), Map.entry("mdash", "—"),
            Map.entry("ndash", "–"), Map.entry("hellip", "…"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("eacute", "é"), Map.entry("egrave", "è"));

    private Html() { }

    public static Page parse(String html, URI base) {
        StringBuilder text = new StringBuilder();
        StringBuilder title = new StringBuilder();
        List<String> links = new ArrayList<>();
        boolean noIndex = false;
        boolean noFollow = false;
        boolean inTitle = false;
        String skipping = null; // name of the element whose content is being skipped
        int i = 0;
        int n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                int next = html.indexOf('<', i);
                if (next < 0) {
                    next = n;
                }
                if (skipping == null || inTitle) {
                    String chunk = decodeEntities(html.substring(i, next));
                    (inTitle ? title : text).append(chunk);
                }
                i = next;
                continue;
            }
            if (html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }
            int end = findTagEnd(html, i + 1);
            String tag = html.substring(i + 1, end);
            i = end + 1;
            boolean closing = tag.startsWith("/");
            String name = tagName(closing ? tag.substring(1) : tag);
            if (name.isEmpty()) {
                continue;
            }
            if (skipping != null) {
                if (name.equals("title")) {
                    inTitle = !closing; // <title> lives inside <head>, which is otherwise skipped
                } else if (closing && name.equals(skipping)) {
                    skipping = null;
                } else if (!closing && name.equals("meta")) {
                    String robots = attr(tag, "name");
                    if ("robots".equalsIgnoreCase(robots)) {
                        String content = String.valueOf(attr(tag, "content")).toLowerCase(Locale.ROOT);
                        noIndex |= content.contains("noindex");
                        noFollow |= content.contains("nofollow");
                    }
                }
                continue;
            }
            if (!closing && SKIP.contains(name) && !tag.endsWith("/")) {
                skipping = name;
                continue;
            }
            if (name.equals("title")) {
                inTitle = !closing;
                continue;
            }
            if (BLOCK.contains(name)) {
                text.append('\n');
            } else {
                text.append(' ');
            }
            if (!closing && name.equals("a")) {
                String href = attr(tag, "href");
                String rel = attr(tag, "rel");
                if (href != null && (rel == null || !rel.toLowerCase(Locale.ROOT).contains("nofollow"))) {
                    String resolved = resolve(base, href);
                    if (resolved != null) {
                        links.add(resolved);
                    }
                }
            }
        }
        return new Page(collapse(title.toString()), collapseText(text.toString()), links, noIndex, noFollow);
    }

    /** Finds the closing '>' of a tag, ignoring any '>' inside quoted attribute values. */
    private static int findTagEnd(String html, int from) {
        char quote = 0;
        for (int i = from; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return html.length() - 1;
    }

    private static String tagName(String tag) {
        int i = 0;
        while (i < tag.length() && (Character.isLetterOrDigit(tag.charAt(i)) || tag.charAt(i) == '-')) {
            i++;
        }
        return tag.substring(0, i).toLowerCase(Locale.ROOT);
    }

    /** Reads attribute {@code name} from a tag's text: href="x", href='x' or href=x. */
    static String attr(String tag, String name) {
        String lower = tag.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = lower.indexOf(name, from);
            if (i < 0) {
                return null;
            }
            from = i + name.length();
            boolean startsWord = i > 0 && Character.isWhitespace(lower.charAt(i - 1));
            int j = from;
            while (j < tag.length() && Character.isWhitespace(tag.charAt(j))) {
                j++;
            }
            if (!startsWord || j >= tag.length() || tag.charAt(j) != '=') {
                continue;
            }
            j++;
            while (j < tag.length() && Character.isWhitespace(tag.charAt(j))) {
                j++;
            }
            if (j >= tag.length()) {
                return "";
            }
            char q = tag.charAt(j);
            if (q == '"' || q == '\'') {
                int end = tag.indexOf(q, j + 1);
                return decodeEntities(tag.substring(j + 1, end < 0 ? tag.length() : end));
            }
            int end = j;
            while (end < tag.length() && !Character.isWhitespace(tag.charAt(end)) && tag.charAt(end) != '/') {
                end++;
            }
            return decodeEntities(tag.substring(j, end));
        }
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int semi = c == '&' ? s.indexOf(';', i) : -1;
            if (semi > i && semi - i <= 10) {
                String name = s.substring(i + 1, semi);
                String rep = null;
                if (name.startsWith("#x") || name.startsWith("#X")) {
                    rep = codePoint(name.substring(2), 16);
                } else if (name.startsWith("#")) {
                    rep = codePoint(name.substring(1), 10);
                } else {
                    rep = ENTITIES.get(name);
                }
                if (rep != null) {
                    sb.append(rep);
                    i = semi;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String codePoint(String digits, int radix) {
        try {
            int cp = Integer.parseInt(digits, radix);
            return Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Resolves a link against the page URL and drops fragments, mailto: and javascript: links. */
    static String resolve(URI base, String href) {
        href = href.trim();
        if (href.isEmpty() || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("javascript:")
                || href.startsWith("tel:")) {
            return null;
        }
        try {
            URI u = base == null ? new URI(href) : base.resolve(href.replace(" ", "%20"));
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return null;
            }
            return UrlNormalizer.normalize(u.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /** Collapses runs of spaces but keeps paragraph breaks, for readable snippets. */
    private static String collapseText(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String l = collapse(line);
            if (!l.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(l);
            }
        }
        return sb.toString();
    }
}
