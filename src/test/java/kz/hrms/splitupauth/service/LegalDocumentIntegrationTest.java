package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.LegalDocumentDto;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.UpdateLegalDocumentRequest;
import kz.hrms.splitupauth.entity.LegalDocument;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full-context integration coverage for the legal documents feature:
 *
 * <ul>
 *   <li>The boot-time seeder creates TERMS and PRIVACY rows on a fresh V40 schema.
 *   <li>Admin PUT bumps the version and writes a LEGAL_DOCUMENT_UPDATED audit row — proving the V40
 *       CHECK constraint update took effect.
 *   <li>Registration without {@code termsAccepted=true} trips @AssertTrue and returns 400 at the
 *       DTO validation layer.
 *   <li>PUT /api/v1/admin/legal/terms with a plain USER bearer token returns 403 (SecurityConfig
 *       ADMIN-only rule for /api/v1/admin/**).
 * </ul>
 */
@TestPropertySource(properties = "app.dev.auto-verify-email=true")
class LegalDocumentIntegrationTest extends AbstractIntegrationTest {

  @Autowired LegalDocumentService legalDocumentService;
  @Autowired AuthService authService;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired ObjectMapper objectMapper;
  @Autowired WebApplicationContext webApplicationContext;
  @Autowired JwtUtil jwtUtil;

  // Spring Security's servlet filter — needs to be added to MockMvc so the
  // ADMIN-only rule for /api/v1/admin/** is enforced, and the JWT filter
  // runs to authenticate our test tokens.
  @Autowired
  @Qualifier("springSecurityFilterChain")
  Filter springSecurityFilterChain;

  private static final AtomicInteger SEQ = new AtomicInteger();
  private final MockHttpServletRequest http = new MockHttpServletRequest();

  private MockMvc mockMvc;

  @BeforeEach
  void buildMvc() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilter(springSecurityFilterChain)
            .build();
  }

  private User newUser(Role role) {
    int n = SEQ.incrementAndGet();
    User u =
        User.builder()
            .email(
                "legal_"
                    + role.name().toLowerCase()
                    + "_"
                    + n
                    + "_"
                    + System.nanoTime()
                    + "@test.kz")
            .password("x")
            .displayName(role.name() + " " + n)
            .role(role)
            .status(UserStatus.ACTIVE)
            .build();
    return userRepository.save(u);
  }

  // ---------------- Seeder + admin update ----------------

  @Test
  void seed_bootPopulatesBothDocuments_onFreshDatabase() {
    LegalDocumentDto terms = legalDocumentService.getPublic(LegalDocument.DocType.TERMS);
    LegalDocumentDto privacy = legalDocumentService.getPublic(LegalDocument.DocType.PRIVACY);

    assertNotNull(terms);
    assertNotNull(privacy);
    assertEquals("terms", terms.getDocType());
    assertEquals("privacy", privacy.getDocType());
    // Bodies loaded from classpath /legal resources, in all three languages.
    assertNotNull(terms.getBodyRu());
    assertFalse(terms.getBodyRu().isBlank());
    assertNotNull(terms.getBodyKz());
    assertNotNull(terms.getBodyEn());
  }

  @Test
  void adminUpdate_bumpsVersion_writesAuditLog_sanitizesHtml() {
    User admin = newUser(Role.ADMIN);
    LegalDocumentDto before = legalDocumentService.adminGet(LegalDocument.DocType.TERMS);
    int startingVersion = before.getVersion();

    long auditBefore =
        jdbcTemplate.queryForObject(
            "select count(*) from admin_action_log where action_type = 'LEGAL_DOCUMENT_UPDATED'",
            Long.class);

    UpdateLegalDocumentRequest req = new UpdateLegalDocumentRequest();
    req.setTitleRu("Пользовательское соглашение <script>alert(1)</script>");
    req.setBodyRu("Новый текст условий\n\nВторой абзац.");

    LegalDocumentDto after =
        legalDocumentService.adminUpdate(admin, LegalDocument.DocType.TERMS, req, http);

    assertEquals(startingVersion + 1, after.getVersion());
    assertEquals(
        "Пользовательское соглашение alert(1)",
        after.getTitleRu(),
        "HTML tags + lone brackets must be stripped server-side");
    assertTrue(after.getBodyRu().startsWith("Новый текст условий"));

    long auditAfter =
        jdbcTemplate.queryForObject(
            "select count(*) from admin_action_log where action_type = 'LEGAL_DOCUMENT_UPDATED'",
            Long.class);
    assertEquals(
        auditBefore + 1,
        auditAfter,
        "Each admin update must append a LEGAL_DOCUMENT_UPDATED audit row (proves V40 CHECK constraint).");
  }

  @Test
  void adminUpdate_partialPayload_leavesOtherLanguagesUnchanged() {
    User admin = newUser(Role.ADMIN);
    LegalDocumentDto before = legalDocumentService.adminGet(LegalDocument.DocType.PRIVACY);
    String originalKz = before.getBodyKz();
    String originalEn = before.getBodyEn();

    UpdateLegalDocumentRequest req = new UpdateLegalDocumentRequest();
    req.setBodyRu("Только русский обновлён");

    LegalDocumentDto after =
        legalDocumentService.adminUpdate(admin, LegalDocument.DocType.PRIVACY, req, http);

    assertEquals("Только русский обновлён", after.getBodyRu());
    assertEquals(
        originalKz,
        after.getBodyKz(),
        "Absent fields in the request must not clear other-language columns.");
    assertEquals(originalEn, after.getBodyEn());
  }

  // ---------------- Registration gating (400) ----------------

  @Test
  void registerRequest_withoutTermsAccepted_failsValidation() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    RegisterRequest req = new RegisterRequest();
    req.setEmail("nope@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName("Nope");
    // termsAccepted left null on purpose.

    Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("termsAccepted")),
        "@AssertTrue must reject a missing termsAccepted flag (→ 400 at the controller).");
  }

  @Test
  void registerRequest_withTermsAcceptedFalse_failsValidation() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    RegisterRequest req = new RegisterRequest();
    req.setEmail("nope@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName("Nope");
    req.setTermsAccepted(false);

    Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("termsAccepted")));
  }

  @Test
  void register_withTermsAccepted_recordsAcceptedAtAndVersions() {
    RegisterRequest req = new RegisterRequest();
    req.setEmail("ok_" + System.nanoTime() + "@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName("OK User");
    req.setTermsAccepted(true);

    authService.register(req);

    User saved = userRepository.findByEmail(req.getEmail()).orElseThrow();
    assertNotNull(saved.getTermsAcceptedAt(), "Registration must stamp terms_accepted_at.");
    // Versions default to the current server-side version when the caller
    // doesn't supply them explicitly.
    assertNotNull(saved.getAcceptedTermsVersion());
    assertNotNull(saved.getAcceptedPrivacyVersion());
  }

  /**
   * Same as above, driven through the /api/v1/auth/register endpoint so we exercise the @Valid →
   * 400 wire behaviour (the primary requirement).
   */
  @Test
  void postRegister_missingTermsAccepted_returns400() throws Exception {
    String body =
        "{\"email\":\"missing_"
            + System.nanoTime()
            + "@test.kz\","
            + "\"password\":\"Test1234\",\"displayName\":\"NoTerms\"}";
    mockMvc
        .perform(
            post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  // ---------------- Authorization on /admin/legal (403) ----------------

  @Test
  void putAdminLegal_asPlainUser_returns403() throws Exception {
    User user = newUser(Role.USER);
    String token = jwtUtil.generateAccessToken(user.getEmail());

    UpdateLegalDocumentRequest body = new UpdateLegalDocumentRequest();
    body.setBodyRu("hijack attempt");

    mockMvc
        .perform(
            put("/api/v1/admin/legal/terms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isForbidden());
  }

  @Test
  void putAdminLegal_asAdmin_returns200() throws Exception {
    User admin = newUser(Role.ADMIN);
    String token = jwtUtil.generateAccessToken(admin.getEmail());

    UpdateLegalDocumentRequest body = new UpdateLegalDocumentRequest();
    body.setBodyRu("Legit admin edit");

    mockMvc
        .perform(
            put("/api/v1/admin/legal/terms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  @Test
  void getPublicLegal_isReachableWithoutAuth() throws Exception {
    mockMvc.perform(get("/api/v1/site/legal/terms")).andExpect(status().isOk());
  }

  /** Unknown docType returns 400 rather than 500. */
  @Test
  void getPublicLegal_unknownDocType_returns400() throws Exception {
    mockMvc.perform(get("/api/v1/site/legal/something-else")).andExpect(status().isBadRequest());
  }
}
