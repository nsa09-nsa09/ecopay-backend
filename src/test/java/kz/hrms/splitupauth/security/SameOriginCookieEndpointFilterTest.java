package kz.hrms.splitupauth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.List;
import kz.hrms.splitupauth.config.CorsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SameOriginCookieEndpointFilterTest {

  private SameOriginCookieEndpointFilter filter;

  @BeforeEach
  void setUp() {
    CorsProperties corsProperties = new CorsProperties();
    corsProperties.setAllowedOrigins(List.of("https://app.ecopay.kz"));
    filter = new SameOriginCookieEndpointFilter(corsProperties);
  }

  @Test
  void allowedOriginPasses() throws ServletException, IOException {
    MockHttpServletResponse response = execute(requestWithCookie("Origin", "https://app.ecopay.kz"));

    assertEquals(200, response.getStatus());
  }

  @Test
  void foreignOriginIsForbidden() throws ServletException, IOException {
    MockHttpServletResponse response = execute(requestWithCookie("Origin", "https://evil.example"));

    assertEquals(403, response.getStatus());
  }

  @Test
  void missingOriginWithAllowedRefererPasses() throws ServletException, IOException {
    MockHttpServletResponse response =
        execute(requestWithCookie("Referer", "https://app.ecopay.kz/account/settings"));

    assertEquals(200, response.getStatus());
  }

  @Test
  void cookieRequestWithoutOriginOrRefererIsForbidden() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
    request.setCookies(new Cookie("ecopay_rt", "refresh-token"));

    MockHttpServletResponse response = execute(request);

    assertEquals(403, response.getStatus());
  }

  @Test
  void bearerOnlyEndpointIsNotChecked() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rooms");

    MockHttpServletResponse response = execute(request);

    assertEquals(200, response.getStatus());
  }

  private MockHttpServletRequest requestWithCookie(String headerName, String headerValue) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
    request.addHeader(headerName, headerValue);
    request.setCookies(new Cookie("ecopay_rt", "refresh-token"));
    return request;
  }

  private MockHttpServletResponse execute(MockHttpServletRequest request)
      throws ServletException, IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }
}
