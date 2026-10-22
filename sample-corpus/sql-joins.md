# SQL joins

A join combines rows from two tables based on a related column. An inner join returns only rows with matching values in both tables. A left join returns every row from the left table and the matching rows from the right table, filling in NULL where there is no match.

Databases execute joins with different algorithms: nested loop joins, hash joins that build a hash table on the smaller input, and merge joins over sorted inputs. The query planner chooses based on table sizes and available indexes.
