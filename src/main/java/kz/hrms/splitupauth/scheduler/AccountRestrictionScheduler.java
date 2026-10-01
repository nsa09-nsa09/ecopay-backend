package kz.hrms.splitupauth.scheduler;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.service.AccountRestrictionTransitions;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountRestrictionScheduler {
  private final UserRepository userRepository;
  private final AccountRestrictionTransitions transitions;

  @Scheduled(fixedDelayString = "${app.scheduler.account-restrictions-delay-ms:60000}")
  public void reconcile() {
    LocalDateTime now = LocalDateTime.now();
    userRepository
        .findDueBanActivationIds(now, PageRequest.of(0, 100))
        .forEach(transitions::activate);
    userRepository
        .findDueBanExpirationIds(now, PageRequest.of(0, 100))
        .forEach(transitions::expire);
  }
}
