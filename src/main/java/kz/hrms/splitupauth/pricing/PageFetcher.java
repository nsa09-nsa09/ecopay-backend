package kz.hrms.splitupauth.pricing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import kz.hrms.splitupauth.entity.PriceSnapshotOutcome;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import kz.hrms.splitupauth.pricing.SafeOutboundUrlPolicy.SafeUrl;
import kz.hrms.splitupauth.pricing.SafeOutboundUrlPolicy.UnsafeOutboundUrlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PageFetcher {

  private final SafeOutboundUrlPolicy urlPolicy;
  private final Map<String, Semaphore> perHostLimit = new ConcurrentHashMap<>();
  private final Semaphore globalLimit;
  private final int perHostConcurrency;

  @Value(
      "${app.pricing.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36}")
  private String userAgent;

  @Value("${app.pricing.timeout-seconds:8}")
  private long timeoutSeconds;

  @Value("${app.pricing.connect-timeout-seconds:3}")
  private long connectTimeoutSeconds;

  @Value("${app.pricing.max-compressed-bytes:2097152}")
  private int maxCompressedBytes;

  @Value("${app.pricing.max-decompressed-bytes:8388608}")
  private int maxDecompressedBytes;

  @Value("${app.pricing.max-header-bytes:32768}")
  private int maxHeaderBytes;

  @Value("${app.pricing.max-redirects:2}")
  private int maxRedirects;

  public PageFetcher(
      SafeOutboundUrlPolicy urlPolicy,
      @Value("${app.pricing.fetcher-max-concurrency:${app.pricing.max-concurrency:3}}")
          int maxConcurrency,
      @Value("${app.pricing.per-domain-concurrency:1}") int perHostConcurrency) {
    this.urlPolicy = urlPolicy;
    this.globalLimit = new Semaphore(Math.max(1, maxConcurrency));
    this.perHostConcurrency = Math.max(1, perHostConcurrency);
  }

  public FetchResult fetch(PriceWatchProvider provider, String etag, String lastModified) {
    long started = System.nanoTime();
    if (Boolean.TRUE.equals(provider.getRequiresJs())) {
      return FetchResult.failed(
          PriceSnapshotOutcome.REQUIRES_JS,
          null,
          "requires_js=true; headless renderer is not enabled");
    }
    SafeUrl current;
    try {
      current = urlPolicy.validate(provider.getUrl());
    } catch (UnsafeOutboundUrlException ex) {
      return FetchResult.failed(ex.getOutcome(), null, sanitizeMessage(ex.getMessage()));
    }

    Set<String> seen = new LinkedHashSet<>();
    Set<String> redirectAllowlist = redirectAllowlist(provider);
    for (int hop = 0; hop <= maxRedirects; hop++) {
      String key = current.sanitizedUrl();
      if (!seen.add(key)) {
        return FetchResult.failed(PriceSnapshotOutcome.REDIRECT_BLOCKED, null, "Redirect loop");
      }
      FetchResult result = fetchOnce(current, provider, etag, lastModified);
      if (result.page() != null || result.notModified()) {
        log.debug(
            "Price fetch provider={} host={} outcome={} durationMs={}",
            provider.getId(),
            current.host(),
            result.outcome(),
            Duration.ofNanos(System.nanoTime() - started).toMillis());
        return result;
      }
      if (!isRedirect(result.httpStatus())) {
        return result;
      }
      if (hop == maxRedirects) {
        return FetchResult.failed(
            PriceSnapshotOutcome.REDIRECT_BLOCKED, result.httpStatus(), "Too many redirects");
      }
      try {
        current = urlPolicy.validateRedirect(current, result.errorMessage(), redirectAllowlist);
      } catch (UnsafeOutboundUrlException ex) {
        return FetchResult.failed(
            PriceSnapshotOutcome.REDIRECT_BLOCKED,
            result.httpStatus(),
            sanitizeMessage(ex.getMessage()));
      }
    }
    return FetchResult.failed(PriceSnapshotOutcome.REDIRECT_BLOCKED, null, "Too many redirects");
  }

  private FetchResult fetchOnce(
      SafeUrl safeUrl, PriceWatchProvider provider, String etag, String lastModified) {
    Semaphore hostLimit =
        perHostLimit.computeIfAbsent(safeUrl.host(), ignored -> new Semaphore(perHostConcurrency));
    boolean globalAcquired = false;
    boolean hostAcquired = false;
    try {
      globalLimit.acquire();
      globalAcquired = true;
      hostLimit.acquire();
      hostAcquired = true;

      try (Socket socket = openSocket(safeUrl)) {
        writeRequest(socket, safeUrl, provider, etag, lastModified);
        HttpResponse response = readResponse(socket.getInputStream());
        if (response.status == 304) {
          return FetchResult.notModified(
              response.status, response.header("etag"), response.header("last-modified"));
        }
        if (isRedirect(response.status)) {
          String location = response.header("location");
          if (location == null || location.isBlank()) {
            return FetchResult.failed(
                PriceSnapshotOutcome.REDIRECT_BLOCKED,
                response.status,
                "Redirect without Location header");
          }
          return FetchResult.failed(
              PriceSnapshotOutcome.REDIRECT_BLOCKED, response.status, location);
        }
        if (response.status == 403 || response.status == 429 || response.status == 451) {
          return FetchResult.failed(
              response.status == 429
                  ? PriceSnapshotOutcome.RATE_LIMITED
                  : PriceSnapshotOutcome.FETCH_FAILED,
              response.status,
              "HTTP " + response.status);
        }
        if (response.status < 200 || response.status >= 300) {
          return FetchResult.fetchFailed(response.status, "HTTP " + response.status);
        }
        String contentType = response.header("content-type");
        if (!isAllowedContentType(contentType)) {
          return FetchResult.failed(
              PriceSnapshotOutcome.UNSUPPORTED_CONTENT_TYPE,
              response.status,
              "Unsupported content type");
        }
        String body = decode(response.body, response.header("content-encoding"), contentType);
        FetchedPage page =
            new FetchedPage(
                safeUrl.sanitizedUrl(),
                response.status,
                body,
                response.headers,
                provider.getExpectedCurrency(),
                provider.getLocale());
        return FetchResult.ok(page, response.header("etag"), response.header("last-modified"));
      }
    } catch (UnsafeOutboundUrlException ex) {
      return FetchResult.failed(ex.getOutcome(), null, sanitizeMessage(ex.getMessage()));
    } catch (TooLargeException ex) {
      return FetchResult.failed(ex.outcome, null, ex.getMessage());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return FetchResult.fetchFailed(null, "Interrupted");
    } catch (IOException ex) {
      return FetchResult.fetchFailed(null, sanitizeMessage(ex.getClass().getSimpleName()));
    } finally {
      if (hostAcquired) hostLimit.release();
      if (globalAcquired) globalLimit.release();
    }
  }

  private Socket openSocket(SafeUrl safeUrl) throws IOException {
    InetAddress selected = safeUrl.addresses().get(0);
    Socket plain = new Socket();
    plain.connect(
        new InetSocketAddress(selected, safeUrl.port()),
        (int) Duration.ofSeconds(connectTimeoutSeconds).toMillis());
    plain.setSoTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
    if (!"https".equals(safeUrl.scheme())) {
      return plain;
    }
    SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
    SSLSocket ssl =
        (SSLSocket) sslFactory.createSocket(plain, safeUrl.host(), safeUrl.port(), true);
    SSLParameters params = ssl.getSSLParameters();
    params.setEndpointIdentificationAlgorithm("HTTPS");
    params.setServerNames(List.of(new SNIHostName(safeUrl.host())));
    ssl.setSSLParameters(params);
    ssl.startHandshake();
    return ssl;
  }

  private void writeRequest(
      Socket socket, SafeUrl safeUrl, PriceWatchProvider provider, String etag, String lastModified)
      throws IOException {
    String path =
        Optional.ofNullable(safeUrl.uri().getRawPath()).filter(s -> !s.isBlank()).orElse("/");
    if (safeUrl.uri().getRawQuery() != null) path += "?" + safeUrl.uri().getRawQuery();
    Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1);
    writer.write("GET " + path + " HTTP/1.1\r\n");
    writer.write("Host: " + safeUrl.host() + "\r\n");
    writer.write("User-Agent: " + userAgent + "\r\n");
    writer.write(
        "Accept: text/html,application/xhtml+xml,application/json,application/ld+json,text/plain;q=0.8\r\n");
    writer.write(
        "Accept-Language: " + Optional.ofNullable(provider.getLocale()).orElse("en") + "\r\n");
    writer.write("Accept-Encoding: gzip, identity\r\n");
    writer.write("Connection: close\r\n");
    if (etag != null && !etag.isBlank()) writer.write("If-None-Match: " + etag + "\r\n");
    if (lastModified != null && !lastModified.isBlank()) {
      writer.write("If-Modified-Since: " + lastModified + "\r\n");
    }
    writer.write("\r\n");
    writer.flush();
  }

  private HttpResponse readResponse(InputStream input) throws IOException {
    CountingInputStream counted = new CountingInputStream(input, maxCompressedBytes);
    String statusLine = readLine(counted);
    if (statusLine == null || !statusLine.startsWith("HTTP/"))
      throw new IOException("bad HTTP status");
    String[] parts = statusLine.split(" ", 3);
    int status = Integer.parseInt(parts[1]);
    Map<String, String> headers = new LinkedHashMap<>();
    String line;
    while ((line = readLine(counted)) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon > 0) {
        headers.put(
            line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
      }
      if (counted.headerBytes > maxHeaderBytes) {
        throw new TooLargeException(
            PriceSnapshotOutcome.RESPONSE_TOO_LARGE, "Response headers too large");
      }
    }
    String length = headers.get("content-length");
    if (length != null) {
      try {
        if (Long.parseLong(length) > maxCompressedBytes) {
          throw new TooLargeException(
              PriceSnapshotOutcome.RESPONSE_TOO_LARGE, "Response too large");
        }
      } catch (NumberFormatException ignored) {
      }
    }
    byte[] body =
        "chunked".equalsIgnoreCase(headers.get("transfer-encoding"))
            ? readChunked(counted)
            : counted.readRemaining(maxCompressedBytes);
    return new HttpResponse(status, headers, body);
  }

  private byte[] readChunked(CountingInputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      String line = readLine(in);
      if (line == null) throw new EOFException("chunk header missing");
      int semi = line.indexOf(';');
      String sizeText = semi >= 0 ? line.substring(0, semi) : line;
      int size = Integer.parseInt(sizeText.trim(), 16);
      if (size == 0) {
        while ((line = readLine(in)) != null && !line.isEmpty()) {}
        return out.toByteArray();
      }
      byte[] chunk = in.readNBytes(size);
      if (chunk.length != size) throw new EOFException("short chunk");
      out.write(chunk);
      if (out.size() > maxCompressedBytes) {
        throw new TooLargeException(PriceSnapshotOutcome.RESPONSE_TOO_LARGE, "Response too large");
      }
      readLine(in);
    }
  }

  private String decode(byte[] bytes, String encoding, String contentType) throws IOException {
    String enc = encoding == null ? "identity" : encoding.trim().toLowerCase(Locale.ROOT);
    InputStream source;
    if (enc.isBlank() || "identity".equals(enc)) {
      source = new ByteArrayInputStream(bytes);
    } else if ("gzip".equals(enc)) {
      try {
        source = new GZIPInputStream(new ByteArrayInputStream(bytes));
      } catch (IOException ex) {
        throw new UnsafeOutboundUrlException(
            PriceSnapshotOutcome.DECOMPRESSION_FAILED, "Gzip decompression failed");
      }
    } else {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.UNSUPPORTED_CONTENT_TYPE, "Unsupported content encoding");
    }
    byte[] plain =
        readLimited(source, maxDecompressedBytes, PriceSnapshotOutcome.RESPONSE_TOO_LARGE);
    return new String(plain, charsetFrom(contentType));
  }

  private static byte[] readLimited(InputStream in, int max, PriceSnapshotOutcome outcome)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
      if (out.size() > max) throw new TooLargeException(outcome, "Response too large");
    }
    return out.toByteArray();
  }

  private static String readLine(CountingInputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int prev = -1;
    int b;
    while ((b = in.read()) != -1) {
      in.headerBytes++;
      if (prev == '\r' && b == '\n') {
        byte[] bytes = out.toByteArray();
        return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
      }
      out.write(b);
      prev = b;
    }
    return out.size() == 0 ? null : out.toString(StandardCharsets.ISO_8859_1);
  }

  private static boolean isRedirect(Integer status) {
    return status != null && status >= 300 && status < 400;
  }

  private static boolean isAllowedContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) return false;
    String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return Set.of(
            "text/html",
            "application/xhtml+xml",
            "application/json",
            "application/ld+json",
            "text/plain")
        .contains(type);
  }

  private static Charset charsetFrom(String contentType) {
    if (contentType == null) return StandardCharsets.UTF_8;
    int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
    if (idx < 0) return StandardCharsets.UTF_8;
    String name = contentType.substring(idx + "charset=".length()).trim();
    int semi = name.indexOf(';');
    if (semi >= 0) name = name.substring(0, semi).trim();
    if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
      name = name.substring(1, name.length() - 1);
    }
    try {
      return Charset.forName(name);
    } catch (Exception ex) {
      return StandardCharsets.UTF_8;
    }
  }

  private static Set<String> redirectAllowlist(PriceWatchProvider provider) {
    if (provider.getExtractorConfig() == null
        || !provider.getExtractorConfig().has("redirectHosts")) {
      return Set.of();
    }
    Set<String> hosts = new LinkedHashSet<>();
    provider
        .getExtractorConfig()
        .get("redirectHosts")
        .forEach(node -> hosts.add(node.asText("").toLowerCase(Locale.ROOT)));
    return hosts;
  }

  private static String sanitizeMessage(String message) {
    if (message == null || message.isBlank()) return null;
    String out = message.replaceAll("https?://[^\\s]+", "[redacted-url]");
    return out.length() <= 240 ? out : out.substring(0, 240);
  }

  public static Map<String, String> emptyHeaders() {
    return new HashMap<>();
  }

  private record HttpResponse(int status, Map<String, String> headers, byte[] body) {
    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  private static class CountingInputStream extends PushbackInputStream {
    private final int max;
    private int total;
    private int headerBytes;

    CountingInputStream(InputStream in, int max) {
      super(in, 1);
      this.max = max;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b != -1 && ++total > max) {
        throw new TooLargeException(PriceSnapshotOutcome.RESPONSE_TOO_LARGE, "Response too large");
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0 && (total += n) > max) {
        throw new TooLargeException(PriceSnapshotOutcome.RESPONSE_TOO_LARGE, "Response too large");
      }
      return n;
    }

    byte[] readRemaining(int max) throws IOException {
      return readLimited(this, max, PriceSnapshotOutcome.RESPONSE_TOO_LARGE);
    }
  }

  private static class TooLargeException extends IOException {
    private final PriceSnapshotOutcome outcome;

    TooLargeException(PriceSnapshotOutcome outcome, String message) {
      super(message);
      this.outcome = outcome;
    }
  }
}
