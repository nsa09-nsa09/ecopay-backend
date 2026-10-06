package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SlugServiceTest {

  @Mock UserRepository userRepository;
  @InjectMocks SlugService service;

  @Test
  void uniqueSlugForFreeBaseReturnsBase() {
    when(userRepository.findBySlug("alice")).thenReturn(Optional.empty());
    assertEquals("alice", service.uniqueSlugFor("Alice"));
  }

  @Test
  void uniqueSlugForCollisionAppendsSuffix() {
    when(userRepository.findBySlug("alice")).thenReturn(Optional.of(User.builder().build()));
    when(userRepository.findBySlug("alice-2")).thenReturn(Optional.empty());

    assertEquals("alice-2", service.uniqueSlugFor("Alice"));
  }

  @Test
  void uniqueSlugForReservedBaseAlsoSuffixed() {
    // "admin" is reserved, so the very first candidate is skipped.
    when(userRepository.findBySlug("admin-2")).thenReturn(Optional.empty());
    assertEquals("admin-2", service.uniqueSlugFor("admin"));
  }

  @Test
  void changeSlugRejectsInvalidPattern() {
    User u = User.builder().id(1L).slug("current").build();
    // Two chars → falls through to "user" fallback but requested was not blank —
    // "user" matches the pattern but hits the reserved branch, so we assert reserved
    // for that case separately. Here we feed a genuinely malformed slug via blank input.
    assertThrows(InvalidRequestException.class, () -> service.changeSlug(u, ""));
  }

  @Test
  void changeSlugRejectsTaken() {
    User u = User.builder().id(1L).slug("current").build();
    when(userRepository.existsBySlugAndIdNot(eq("bob"), eq(1L))).thenReturn(true);

    assertThrows(ResourceConflictException.class, () -> service.changeSlug(u, "bob"));
  }

  @Test
  void changeSlugRejectsReserved() {
    User u = User.builder().id(1L).slug("current").build();
    // "admin" is in the reserved set. existsBySlugAndIdNot won't be consulted because the
    // reserved check short-circuits, so a lenient stub keeps the test resilient to reordering.
    lenient().when(userRepository.existsBySlugAndIdNot(eq("admin"), eq(1L))).thenReturn(false);

    assertThrows(ResourceConflictException.class, () -> service.changeSlug(u, "admin"));
  }

  @Test
  void changeSlugAcceptsAndNormalizes() {
    User u = User.builder().id(1L).slug("current").build();
    when(userRepository.existsBySlugAndIdNot(eq("john-doe"), eq(1L))).thenReturn(false);

    String result = service.changeSlug(u, "John Doe");

    assertEquals("john-doe", result);
    assertEquals("john-doe", u.getSlug());
  }
}
