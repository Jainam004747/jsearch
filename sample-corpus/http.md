# HTTP

HTTP is the request-response protocol of the web. A client sends a request with a method such as GET or POST, a path and headers; the server replies with a status code, headers and a body. Common status codes are 200 OK, 301 and 302 redirects, 404 Not Found and 500 Internal Server Error.

HTTP/1.1 keeps connections open for multiple requests. HTTP/2 multiplexes many requests over one connection, and HTTP/3 runs over QUIC instead of TCP. Web crawlers use HTTP to download pages and must respect robots.txt and rate limits.
