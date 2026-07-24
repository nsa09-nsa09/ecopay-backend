package kz.hrms.splitupauth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import kz.hrms.splitupauth.config.CorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class SameOriginCookieEndpointFilter extends OncePerRequestFilter {

  private static final String REFRESH_COOKIE_NAME = "ecopay_rt";
  private static final Set<String> COOKIE_ENDPOINTS =
      Set.of("/api/v1/auth/refresh", "/api/v1/auth/logout");

  private final CorsProperties corsProperties;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!requiresCheck(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    SameOriginDecision decision = evaluate(request);
    if (!decision.allowed()) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, decision.reason());
      return;
    }

    filterChain.doFilter(request, response);
  }

  private boolean requiresCheck(HttpServletRequest request) {
    return HttpMethod.POST.matches(request.getMethod()) && COOKIE_ENDPOINTS.contains(path(request));
  }

  private SameOriginDecision evaluate(HttpServletRequest request) {
    Set<String> allowedOrigins = normalizedAllowedOrigins();
    String origin = request.getHeader("Origin");
    if (origin != null && !origin.isBlank()) {
      String normalized = normalizeOrigin(origin);
      if (normalized != null && allowedOrigins.contains(normalized)) {
        return SameOriginDecision.allow();
      }
      return SameOriginDecision.deny("Cross-site cookie endpoint request");
    }

    String referer = request.getHeader("Referer");
    if (referer != null && !referer.isBlank()) {
      String refererOrigin = originFromReferer(referer);
      if (refererOrigin != null && allowedOrigins.contains(refererOrigin)) {
        return SameOriginDecision.allow();
      }
      return SameOriginDecision.deny("Invalid same-origin referer");
    }

    if (hasRefreshCookie(request)) {
      return SameOriginDecision.deny("Missing Origin or Referer for cookie endpoint request");
    }
    return SameOriginDecision.allow();
  }

  private Set<String> normalizedAllowedOrigins() {
    return corsProperties.getAllowedOrigins().stream()
        .map(SameOriginCookieEndpointFilter::normalizeOrigin)
        .filter(origin -> origin != null && !origin.equals("*"))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static boolean hasRefreshCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    for (Cookie cookie : cookies) {
      if (REFRESH_COOKIE_NAME.equals(cookie.getName())
          && cookie.getValue() != null
          && !cookie.getValue().isBlank()) {
        return true;
      }
    }
    return false;
  }

  private static String originFromReferer(String referer) {
    try {
      URI uri = URI.create(referer.trim());
      if (uri.getScheme() == null || uri.getHost() == null) {
        return null;
      }
      return normalizeOrigin(uri.getScheme() + "://" + uri.getAuthority());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String normalizeOrigin(String value) {
    try {
      String trimmed = value == null ? "" : value.trim();
      URI uri = URI.create(trimmed);
      String rawPath = uri.getRawPath();
      if (uri.getScheme() == null
          || uri.getHost() == null
          || (rawPath != null && !rawPath.isBlank())) {
        return null;
      }
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https")) {
        return null;
      }
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      if (port == -1
          || (scheme.equals("https") && port == 443)
          || (scheme.equals("http") && port == 80)) {
        return scheme + "://" + host;
      }
      return scheme + "://" + host + ":" + port;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private record SameOriginDecision(boolean allowed, String reason) {
    static SameOriginDecision allow() {
      return new SameOriginDecision(true, "");
    }

    static SameOriginDecision deny(String reason) {
      return new SameOriginDecision(false, reason);
    }
  }
}
