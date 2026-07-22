package kz.hrms.splitupauth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The JWT subject switched from email to the immutable publicId when email became optional. The
 * filter must resolve both: publicId for new tokens, email for sessions minted before the switch.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterSubjectTest {

  @Mock JwtUtil jwtUtil;
  @Mock UserRepository userRepository;

  @InjectMocks JwtAuthenticationFilter filter;

  private final User user =
      User.builder()
          .id(1L)
          .publicId("pubAbc123XYZ0")
          .email("user@test.kz")
          .role(Role.USER)
          .status(UserStatus.ACTIVE)
          .build();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletRequest bearer() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer jwt");
    return request;
  }

  @Test
  void publicIdSubject_resolvesViaFindByPublicId() throws Exception {
    when(jwtUtil.extractUsername("jwt")).thenReturn("pubAbc123XYZ0");
    when(userRepository.findByPublicId("pubAbc123XYZ0")).thenReturn(Optional.of(user));
    when(jwtUtil.validateToken("jwt", "pubAbc123XYZ0")).thenReturn(true);

    filter.doFilter(bearer(), new MockHttpServletResponse(), new MockFilterChain());

    assertEquals(user, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    verify(userRepository, never()).findByEmail(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void legacyEmailSubject_resolvesViaFindByEmail() throws Exception {
    when(jwtUtil.extractUsername("jwt")).thenReturn("user@test.kz");
    when(userRepository.findByEmail("user@test.kz")).thenReturn(Optional.of(user));
    when(jwtUtil.validateToken("jwt", "user@test.kz")).thenReturn(true);

    filter.doFilter(bearer(), new MockHttpServletResponse(), new MockFilterChain());

    assertEquals(user, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
  }

  @Test
  void unknownSubject_leavesContextEmpty() throws Exception {
    when(jwtUtil.extractUsername("jwt")).thenReturn("pubUnknown000");
    when(userRepository.findByPublicId("pubUnknown000")).thenReturn(Optional.empty());

    filter.doFilter(bearer(), new MockHttpServletResponse(), new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }
}
