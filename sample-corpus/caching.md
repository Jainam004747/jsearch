# Caching

A cache stores the results of expensive operations so later requests can be served faster. In the cache-aside pattern, the application first checks the cache; on a miss it reads from the database, then writes the result into the cache with an expiry time.

The hardest part is invalidation: when the underlying data changes, cached copies become stale. Common strategies are short TTLs, deleting the cache entry on every write, and versioned keys. Eviction policies such as least recently used (LRU) decide what to remove when memory is full.
