# How compilers work

A compiler translates source code into machine code in stages. The lexer splits text into tokens. The parser checks the grammar and builds an abstract syntax tree. Semantic analysis checks types and resolves names using symbol tables. Many compilers then produce an intermediate representation, apply optimizations such as constant folding and dead code elimination, and finally generate assembly for the target processor.

Recursive descent parsers, where each grammar rule is a function, are popular because they are easy to write and give good error messages. GCC and Clang both use hand-written recursive descent parsers.
