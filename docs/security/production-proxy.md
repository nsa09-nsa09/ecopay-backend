# Production Proxy Notes

The backend must only be reachable through the production reverse proxy.

- Do not publish the Spring Boot port directly to the internet.
- The proxy must overwrite incoming `X-Forwarded-*` headers instead of appending untrusted client values.
- `X-Forwarded-For` may be used only from the trusted proxy chain.
- Security-sensitive absolute URLs must come from configured public URLs (`APP_BASE_URL`, `APP_FRONTEND_URL`, FreedomPay callback env vars), not from arbitrary `Host` headers.
- Keep `server.forward-headers-strategy=framework` in production so Spring sees the public scheme and host provided by the trusted proxy.
- Strip query strings from `/ws` access logs because browser WebSocket handshakes may contain bearer tokens during legacy clients or diagnostics.
