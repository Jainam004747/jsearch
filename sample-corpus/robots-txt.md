# robots.txt and polite crawling

The robots.txt file, placed at the root of a website, tells crawlers which paths they may fetch. Rules are grouped by user agent, with Disallow and Allow lines; when rules conflict, the most specific (longest) matching path wins. Some sites add a Crawl-delay to ask bots to slow down.

Polite crawlers also identify themselves with a clear user agent, limit how often they contact each host, follow redirects carefully, and avoid crawling infinite calendars or session-id URLs.
