package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.CreateStoryRequest;
import kz.hrms.splitupauth.dto.StoryDto;
import kz.hrms.splitupauth.dto.UpdateStoryRequest;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.StoryStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

/** B6: story CTA URL must be an in-app path or an https:// URL; dangerous targets are rejected. */
class StoryServiceCtaUrlTest extends AbstractIntegrationTest {

  @Autowired StoryService storyService;
  @Autowired UserRepository userRepository;

  private static final AtomicInteger SEQ = new AtomicInteger();

  private User admin() {
    int n = SEQ.incrementAndGet();
    return userRepository.save(
        User.builder()
            .email("story_admin_" + n + "_" + System.nanoTime() + "@t.kz")
            .password("x")
            .displayName("Story Admin " + n)
            .role(Role.ADMIN)
            .status(UserStatus.ACTIVE)
            .build());
  }

  private CreateStoryRequest req(String ctaUrl) {
    CreateStoryRequest r = new CreateStoryRequest();
    r.setTitleRu("История");
    r.setStatus(StoryStatus.DRAFT);
    r.setCtaUrl(ctaUrl);
    return r;
  }

  @Test
  void relativePathIsAccepted() {
    StoryDto dto = storyService.create(admin(), req("/rooms/42"), new MockHttpServletRequest());
    assertEquals("/rooms/42", dto.getCtaUrl());
  }

  @Test
  void httpsUrlIsAccepted() {
    StoryDto dto =
        storyService.create(admin(), req("https://ecopay.kz/promo"), new MockHttpServletRequest());
    assertEquals("https://ecopay.kz/promo", dto.getCtaUrl());
  }

  @Test
  void blankCtaIsAllowed() {
    StoryDto dto = storyService.create(admin(), req(null), new MockHttpServletRequest());
    assertEquals(null, dto.getCtaUrl());
  }

  @Test
  void javascriptSchemeIsRejected() {
    InvalidRequestException ex =
        assertThrows(
            InvalidRequestException.class,
            () ->
                storyService.create(
                    admin(), req("javascript:alert(1)"), new MockHttpServletRequest()));
    assertEquals("INVALID_CTA_URL", ex.getCode());
  }

  @Test
  void protocolRelativeAndHttpAndBackslashAreRejected() {
    for (String bad : new String[] {"//evil.com", "/\\evil.com", "http://evil.com"}) {
      InvalidRequestException ex =
          assertThrows(
              InvalidRequestException.class,
              () -> storyService.create(admin(), req(bad), new MockHttpServletRequest()),
              bad);
      assertEquals("INVALID_CTA_URL", ex.getCode(), bad);
    }
  }

  @Test
  void updateAlsoValidatesCtaUrl() {
    StoryDto created = storyService.create(admin(), req("/ok"), new MockHttpServletRequest());
    UpdateStoryRequest patch = new UpdateStoryRequest();
    patch.setCtaUrl("javascript:void(0)");
    InvalidRequestException ex =
        assertThrows(
            InvalidRequestException.class,
            () ->
                storyService.update(created.getId(), admin(), patch, new MockHttpServletRequest()));
    assertEquals("INVALID_CTA_URL", ex.getCode());
  }
}
