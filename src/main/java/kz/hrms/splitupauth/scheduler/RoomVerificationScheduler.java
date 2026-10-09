package kz.hrms.splitupauth.scheduler;

import java.time.Duration;
import kz.hrms.splitupauth.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoomVerificationScheduler {

  private final RoomService roomService;
  private final SchedulerLock schedulerLock;

  @Scheduled(fixedDelayString = "${app.scheduler.room-verification-delay-ms:60000}")
  public void moveStartedRoomsToVerification() {
    schedulerLock.runExclusive("room-verification", Duration.ofMinutes(5), this::moveDueRooms);
  }

  private void moveDueRooms() {
    int movedRooms = roomService.moveStartedOpenRoomsToVerification();

    if (movedRooms > 0) {
      log.info("Moved {} room(s) from OPEN to IN_VERIFICATION", movedRooms);
    }
  }
}
