# Redis

Redis is an in-memory data store used as a cache, message broker and database. Because data lives in RAM, reads and writes typically complete in well under a millisecond. Values can be strings, lists, hashes, sets and sorted sets, and any key can be given a time to live (TTL) after which it expires automatically.

Redis processes commands on a single thread using an event loop, which avoids locking. For durability it offers snapshots (RDB files) and an append-only file (AOF) that logs every write. Clients speak the RESP protocol over TCP.
