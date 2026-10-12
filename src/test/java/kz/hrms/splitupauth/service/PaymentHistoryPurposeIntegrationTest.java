package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.dto.PaymentHistoryItemDto;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentPurpose;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** B11: the payment history must carry each intent's purpose (INITIAL/RENEWAL/RECURRING). */
class PaymentHistoryPurposeIntegrationTest extends AbstractIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private RoomRepository roomRepository;
  @Autowired private RoomMemberRepository roomMemberRepository;
  @Autowired private PaymentIntentRepository paymentIntentRepository;
  @Autowired private ServiceRepository serviceRepository;
  @Autowired private PaymentHistoryService paymentHistoryService;

  @Test
  void historyExposesIntentPurposeForEachKind() {
    ServiceEntity service = serviceRepository.findAll().get(0); // seeded catalog
    User user =
        userRepository.save(
            User.builder()
                .email("hist_" + System.nanoTime() + "@test.kz")
                .password("x")
                .displayName("History User")
                .build());
    Room room =
        roomRepository.save(
            Room.builder()
                .owner(user)
                .service(service)
                .roomType(RoomType.DIGITAL)
                .title("History Room")
                .maxMembers(2)
                .periodType(PeriodType.MONTHLY)
                .startDate(LocalDateTime.now().plusDays(5))
                .build());
    RoomMember member =
        roomMemberRepository.save(
            RoomMember.builder().room(room).user(user).status(MemberStatus.ACTIVE).build());

    persistIntent(user, member, PaymentIntentPurpose.INITIAL, "ik-init-");
    persistIntent(user, member, PaymentIntentPurpose.RENEWAL, "ik-renew-");
    persistIntent(user, member, PaymentIntentPurpose.RECURRING, "ik-recur-");

    PageResponse<PaymentHistoryItemDto> history =
        paymentHistoryService.history(user, 0, 50, "PAYMENT", null, null, null);
    Set<String> purposes =
        history.getItems().stream()
            .map(PaymentHistoryItemDto::getPurpose)
            .collect(Collectors.toSet());

    assertTrue(
        purposes.containsAll(Set.of("INITIAL", "RENEWAL", "RECURRING")),
        "expected all purposes, got " + purposes);
  }

  private void persistIntent(
      User user, RoomMember member, PaymentIntentPurpose purpose, String keyPrefix) {
    paymentIntentRepository.save(
        PaymentIntent.builder()
            .idempotencyKey(keyPrefix + System.nanoTime())
            .roomMember(member)
            .user(user)
            .amount(new BigDecimal("1000.00"))
            .status(PaymentIntentStatus.SUCCESS)
            .purpose(purpose)
            .build());
  }
}
