package com.jainam.jsearch.crawl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotsTxtTest {
    private static final String ROBOTS = """
            # comments are ignored
            User-agent: *
            Disallow: /private/
            Allow: /private/press/
            Disallow: /*.pdf$
            Crawl-delay: 2

            User-agent: BadBot
            Disallow: /
            """;

    @Test
    void longestMatchWinsAndAllowBeatsDisallow() {
        RobotsTxt r = RobotsTxt.parse(ROBOTS, "jsearch-bot");
        assertTrue(r.allowed("/"));
        assertFalse(r.allowed("/private/notes.html"));
        assertTrue(r.allowed("/private/press/release.html"));
        assertFalse(r.allowed("/files/menu.pdf"));
        assertTrue(r.allowed("/files/menu.pdf.html"));
        assertEquals(2.0, r.crawlDelaySeconds());
    }

    @Test
    void specificGroupOverridesGeneric() {
        RobotsTxt r = RobotsTxt.parse(ROBOTS, "BadBot/2.0");
        assertFalse(r.allowed("/anything"));
        assertTrue(RobotsTxt.parse("User-agent: *\nDisallow:\n", "x").allowed("/everything"));
    }
}
