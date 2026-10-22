# Hash tables

A hash table maps keys to values using a hash function that turns each key into an array index. Lookups, inserts and deletes take constant time on average.

When two keys hash to the same index, a collision occurs. Separate chaining stores colliding entries in a linked list per bucket; open addressing probes for the next free slot, for example with linear probing. As the table fills, collisions increase, so implementations resize and rehash once the load factor passes a threshold such as 0.75.
