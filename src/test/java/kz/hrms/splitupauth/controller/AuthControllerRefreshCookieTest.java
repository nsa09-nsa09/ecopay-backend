package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kz.hrms.splitupauth.dto.AuthResponse;
import kz.hrms.splitupauth.exception.GlobalExceptionHandler;
import kz.hrms.splitupauth.service.AuthService;
import kz.hrms.splitupauth.service.PhoneVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AuthControllerRefreshCookieTest {

  @Mock private AuthService authService;
  @Mock private PhoneVerificationService phoneVerificationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AuthController controller = new AuthController(authService, phoneVerificationService);
    ReflectionTestUtils.setField(controller, "refreshExpirationMs", 604800000L);
    ReflectionTestUtils.setField(controller, "refreshCookieSecure", true);
    ReflectionTestUtils.setField(controller, "refreshTokenBodyEnabled", false);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void loginSetsRefreshCookieButDoesNotExposeRefreshTokenInJsonBody() throws Exception {
    when(authService.login(any()))
        .thenReturn(
            AuthResponse.builder()
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .build());

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"user@example.com","password":"password123"}
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn();

    String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertNotNull(setCookie);
    assertTrue(setCookie.contains("ecopay_rt=refresh-token"));
    assertTrue(setCookie.contains("HttpOnly"));
    assertFalse(result.getResponse().getContentAsString().contains("refreshToken"));
  }
}
