# B-trees and B+trees

A B-tree is a balanced search tree designed for storage systems that read data in blocks, such as disks. Each node holds many keys and has many children, so the tree stays very shallow: with hundreds of children per node, millions of keys fit in three or four levels.

In a B+tree, used by most relational databases, all values live in the leaf nodes and the leaves are linked together. Internal nodes only guide the search. The linked leaves make range scans efficient, because the database can walk from one leaf to the next without returning to the root. When a node fills up it splits, which keeps the tree balanced.
