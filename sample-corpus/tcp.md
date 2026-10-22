# TCP

TCP, the Transmission Control Protocol, provides a reliable, ordered byte stream between two programs over a network. A connection begins with a three-way handshake: SYN, SYN-ACK, ACK.

TCP numbers every byte, acknowledges what it receives, and retransmits lost segments. Flow control prevents a fast sender from overwhelming a slow receiver, and congestion control slows down when the network is overloaded. Because TCP is a byte stream, it does not preserve message boundaries: applications must frame their own messages, for example with length prefixes or delimiters.
