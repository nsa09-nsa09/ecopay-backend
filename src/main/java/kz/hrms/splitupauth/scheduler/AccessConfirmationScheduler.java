package kz.hrms.splitupauth.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.service.AccessConfirmationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccessConfirmationScheduler {

  private final AccessConfirmationService accessConfirmationService;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${app.access.deemed-confirmation-delay-ms:60000}")
  public void processDueConfirmations() {
    accessConfirmationService.processDue(LocalDateTime.now(clock), 100);
  }
}
