# Garbage collection

Garbage collection automatically frees memory that a program can no longer reach. A tracing collector starts from roots such as global variables and the stack, marks every object it can reach, and reclaims the rest.

Generational collectors, used by the Java virtual machine, rely on the observation that most objects die young. New objects are allocated in a young generation that is collected often and cheaply; objects that survive are promoted to an old generation collected less frequently. Modern collectors such as G1 and ZGC aim for short pause times.
