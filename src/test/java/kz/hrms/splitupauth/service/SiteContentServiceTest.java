package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import kz.hrms.splitupauth.dto.SiteContentDto;
import kz.hrms.splitupauth.entity.SiteContent;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.SiteContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SiteContentServiceTest {

  @Mock private SiteContentRepository repository;
  @Mock private AdminActionLogRepository adminActionLogRepository;

  private SiteContentService service;

  @BeforeEach
  void setUp() {
    service = new SiteContentService(repository, adminActionLogRepository, new ObjectMapper());
    ReflectionTestUtils.setField(service, "supportEmail", "support@ecopay.kz");
  }

  @Test
  void getAboutFallsBackToConfiguredSupportEmailWithoutPersistingIt() {
    SiteContent content =
        SiteContent.builder()
            .id(SiteContent.SINGLETON_ID)
            .companyName("EcoPay")
            .title("About")
            .mission("Mission")
            .description("Description")
            .contactEmail(" ")
            .contactPhone(null)
            .build();
    when(repository.findById(SiteContent.SINGLETON_ID)).thenReturn(Optional.of(content));

    SiteContentDto dto = service.getAbout();

    assertEquals("support@ecopay.kz", dto.getContactEmail());
    assertEquals(" ", content.getContactEmail());
    verify(repository, never()).save(content);
  }
}
