# How search engines rank pages

A search engine crawls pages, builds an inverted index that maps each word to the documents containing it, and ranks matching documents for every query. Classic ranking functions such as TF-IDF and BM25 reward documents where query terms appear often, discount very common words with inverse document frequency, and normalise for document length.

Web search engines combine text relevance with many other signals, such as links between pages and freshness. Search quality is evaluated with metrics like precision, mean reciprocal rank and nDCG against human relevance judgments.
