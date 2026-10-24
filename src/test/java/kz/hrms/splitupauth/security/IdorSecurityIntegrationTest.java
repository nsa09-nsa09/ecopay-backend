package kz.hrms.splitupauth.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutStatus;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * P12 (IDOR): a user may never read or act on another user's money objects, and SUPPORT may never
 * reach the ADMIN-only finance / dev areas. Exercises the real security filter chain + controllers
 * + ownership checks over a real database, so a missing guard surfaces as a 200 here.
 */
class IdorSecurityIntegrationTest extends AbstractIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private FilterChainProxy springSecurityFilterChain;
  @Autowired private UserRepository userRepository;
  @Autowired private PayoutRepository payoutRepository;
  @Autowired private JwtUtil jwtUtil;

  private MockMvc mockMvc;
  private String tokenA;
  private String tokenB;
  private String tokenSupport;
  private Long payoutOfA;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    // Unique per setup: the Testcontainers DB is shared across test methods and rows are committed.
    String uniq = Long.toString(System.nanoTime());
    User a = saveUser("idor-a-" + uniq + "@test.kz", Role.USER);
    User b = saveUser("idor-b-" + uniq + "@test.kz", Role.USER);
    User support = saveUser("idor-support-" + uniq + "@test.kz", Role.SUPPORT);
    tokenA = jwtUtil.generateAccessToken(a.getPublicId());
    tokenB = jwtUtil.generateAccessToken(b.getPublicId());
    tokenSupport = jwtUtil.generateAccessToken(support.getPublicId());

    Payout p =
        payoutRepository.save(
            Payout.builder()
                .user(a)
                .amount(new BigDecimal("5000.00"))
                .originalAmount(new BigDecimal("5000.00"))
                .payableAmount(new BigDecimal("5000.00"))
                .currency("KZT")
                .status(PayoutStatus.PENDING)
                .idempotencyKey("idor-payout-" + System.nanoTime())
                .build());
    payoutOfA = p.getId();
  }

  private User saveUser(String email, Role role) {
    return userRepository.save(
        User.builder()
            .email(email)
            .password("x")
            .displayName("idor")
            .role(role)
            .status(UserStatus.ACTIVE)
            .build());
  }

  private ResultMatcher forbiddenOrNotFound() {
    return result -> {
      int s = result.getResponse().getStatus();
      if (s != 403 && s != 404) {
        throw new AssertionError("expected 403 or 404 but was " + s);
      }
    };
  }

  // ---- money endpoints with {id}: a foreign user must not read/act on them ----

  @Test
  void foreignUserCannotReadAnotherUsersPayout() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/payouts/" + payoutOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
        .andExpect(status().isForbidden());
    // owner still can
    mockMvc
        .perform(
            get("/api/v1/payouts/" + payoutOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
        .andExpect(status().isOk());
  }

  @Test
  void foreignOrUnknownMoneyIdsAreDeniedForOtherUsers() throws Exception {
    mockMvc
        .perform(get("/api/v1/payouts/99999999").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
        .andExpect(forbiddenOrNotFound());
    mockMvc
        .perform(get("/api/v1/refunds/99999999").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
        .andExpect(forbiddenOrNotFound());
    mockMvc
        .perform(
            get("/api/v1/payments/intents/99999999")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
        .andExpect(forbiddenOrNotFound());
    mockMvc
        .perform(
            delete("/api/v1/payouts/methods/99999999")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
        .andExpect(forbiddenOrNotFound());
    mockMvc
        .perform(
            post("/api/v1/payouts/methods/binding/99999999/confirm")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
        .andExpect(forbiddenOrNotFound());
  }

  // ---- /admin/finance/** and /admin/dev/** are ADMIN-only: SUPPORT (and USER) get 403 ----

  @Test
  void supportCannotReachAdminFinanceOrDev() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/finance/config")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenSupport)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/admin/finance/stuck")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenSupport)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/admin/dev/anything")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenSupport)))
        .andExpect(status().isForbidden());
  }

  @Test
  void plainUserCannotReachAdminFinance() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/finance/config").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
        .andExpect(status().isForbidden());
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
