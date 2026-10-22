package com.jainam.jsearch.text;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Puts URLs in one canonical form, so the crawler does not fetch the same page twice under
 * different spellings: "HTTP://Example.com:80/a/../b/#top" → "http://example.com/b/".
 */
public final class UrlNormalizer {
    private UrlNormalizer() { }

    public static String normalize(String url) {
        try {
            URI u = new URI(url).normalize(); // resolves "." and ".." path segments
            String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1; // default ports are implied
            }
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            String query = u.getRawQuery();
            return new URI(scheme, null, host, port, null, null, null).toString()
                    + path + (query == null ? "" : "?" + query); // the #fragment is dropped
        } catch (URISyntaxException | IllegalArgumentException e) {
            return url;
        }
    }

    public static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
