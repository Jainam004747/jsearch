# Database transactions and ACID

A transaction groups several operations so they succeed or fail together. ACID describes the guarantees: atomicity (all or nothing), consistency (rules and constraints hold), isolation (concurrent transactions do not interfere) and durability (once committed, data survives crashes).

Durability is commonly implemented with a write-ahead log: changes are appended to a log and flushed to disk with fsync before the transaction is reported as committed. After a crash, the database replays the log to recover committed work and discards incomplete transactions.
