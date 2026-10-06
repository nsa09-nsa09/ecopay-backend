package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SavedCard;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import kz.hrms.splitupauth.scheduler.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class RecurringChargeServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private PaymentIntentRepository paymentIntentRepository;
  @Mock private SavedCardRepository savedCardRepository;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PaymentGateway gateway;
  @Mock private PaymentEventLogger eventLogger;
  @Mock private PaymentService paymentService;
  @Mock private LiveMoneyGuard liveMoneyGuard;
  @Mock private SchedulerLock schedulerLock;
  @Mock private PlatformTransactionManager transactionManager;

  private RecurringChargeService service;
  private RoomMember member;
  private final Map<String, PaymentIntent> intentsByKey = new HashMap<>();

  @BeforeEach
  void setUp() {
    service =
        new RecurringChargeService(
            roomMemberRepository,
            paymentIntentRepository,
            savedCardRepository,
            gatewayRegistry,
            eventLogger,
            paymentService,
            liveMoneyGuard,
            schedulerLock,
            Clock.fixed(NOW, ZONE),
            transactionManager);
    ReflectionTestUtils.setField(service, "recurringEnabled", true);

    User user = User.builder().id(1L).status(UserStatus.ACTIVE).build();
    Room room = Room.builder().id(2L).title("Netflix").periodType(PeriodType.MONTHLY).build();
    LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
    member =
        RoomMember.builder()
            .id(3L)
            .user(user)
            .room(room)
            .status(MemberStatus.ACTIVE)
            .billingAnchorAt(now.minusMonths(1))
            .billingPeriodStart(now.minusMonths(1))
            .nextBillingAt(now.plusDays(1))
            .recurringRetryCount(0)
            .build();
    PaymentIntent lastSuccess =
        PaymentIntent.builder()
            .id(10L)
            .amount(new BigDecimal("2322.50"))
            .commissionAmount(new BigDecimal("500.00"))
            .status(PaymentIntentStatus.SUCCESS)
            .createdAt(now.minusMonths(1))
            .build();
    SavedCard card =
        SavedCard.builder()
            .id(5L)
            .providerToken("profile-1")
            .status(SavedCardStatus.ACTIVE)
            .build();

    lenient().when(roomMemberRepository.findWithLockById(3L)).thenReturn(Optional.of(member));
    lenient()
        .when(
            paymentIntentRepository.findFirstByRoomMemberAndStatusOrderByCreatedAtDesc(
                member, PaymentIntentStatus.SUCCESS))
        .thenReturn(Optional.of(lastSuccess));
    lenient()
        .when(savedCardRepository.findByUserAndIsDefaultTrueAndStatus(user, SavedCardStatus.ACTIVE))
        .thenReturn(Optional.of(card));
    lenient().when(gatewayRegistry.defaultGateway()).thenReturn(gateway);
    lenient().when(gateway.providerName()).thenReturn("freedompay");
    lenient()
        .when(paymentIntentRepository.findByIdempotencyKey(anyString()))
        .thenAnswer(i -> Optional.ofNullable(intentsByKey.get(i.<String>getArgument(0))));
    lenient()
        .when(paymentIntentRepository.save(any()))
        .thenAnswer(
            i -> {
              PaymentIntent intent = i.getArgument(0);
              if (intent.getId() == null) intent.setId(100L + intentsByKey.size());
              intentsByKey.put(intent.getIdempotencyKey(), intent);
              return intent;
            });
    lenient()
        .when(paymentIntentRepository.findWithLockById(any()))
        .thenAnswer(
            i ->
                intentsByKey.values().stream()
                    .filter(p -> p.getId().equals(i.getArgument(0)))
                    .findFirst());
    lenient()
        .when(
            paymentIntentRepository.findFirstByRoomMember_IdAndStatusInOrderByCreatedAtDesc(
                eq(3L), any()))
        .thenAnswer(
            i -> {
              List<PaymentIntentStatus> statuses = i.getArgument(1);
              return intentsByKey.values().stream()
                  .filter(p -> statuses.contains(p.getStatus()))
                  .findFirst();
            });
  }

  @Test
  void providerOkIsAcceptanceNotCapture_periodDoesNotAdvance() {
    when(gateway.chargeWithToken(any(), eq("profile-1")))
        .thenReturn(
            GatewayChargeResponse.builder()
                .success(true)
                .captureConfirmed(false)
                .externalPaymentId("rec-1")
                .build());
    LocalDateTime before = member.getNextBillingAt();

    service.tryAutoCharge(3L);

    PaymentIntent attempt = intentsByKey.values().iterator().next();
    assertEquals(PaymentIntentStatus.PENDING, attempt.getStatus());
    assertEquals("rec-1", attempt.getExternalPaymentId());
    assertEquals(before, member.getNextBillingAt());
    verify(paymentService, never())
        .finalizeSuccessfulPayment(any(), any(), any(), any(), any(), any(), any(), anyString());
  }

  @Test
  void duplicateRunWhileAttemptIsOpenNeverChargesTwice() {
    when(gateway.chargeWithToken(any(), anyString()))
        .thenReturn(
            GatewayChargeResponse.builder().success(true).externalPaymentId("rec-1").build());

    service.tryAutoCharge(3L);
    service.tryAutoCharge(3L);
    service.tryAutoCharge(3L);

    verify(gateway, times(1)).chargeWithToken(any(), anyString());
    assertEquals(1, intentsByKey.size());
  }

  @Test
  void capturedAttemptAdvancesThePeriodOnTheNextRun() {
    when(gateway.chargeWithToken(any(), anyString()))
        .thenReturn(
            GatewayChargeResponse.builder().success(true).externalPaymentId("rec-1").build());
    LocalDateTime before = member.getNextBillingAt();
    service.tryAutoCharge(3L);

    // The result callback / reconciliation captured it.
    intentsByKey.values().iterator().next().setStatus(PaymentIntentStatus.SUCCESS);
    service.tryAutoCharge(3L);

    assertEquals(before.plusMonths(1), member.getNextBillingAt());
    verify(gateway, times(1)).chargeWithToken(any(), anyString());
  }

  @Test
  void ambiguousProviderResultIsUnknownAndNotRetriedBlindly() {
    when(gateway.chargeWithToken(any(), anyString()))
        .thenThrow(new IllegalStateException("read timeout"));

    service.tryAutoCharge(3L);
    service.tryAutoCharge(3L);

    PaymentIntent attempt = intentsByKey.values().iterator().next();
    assertEquals(PaymentIntentStatus.UNKNOWN, attempt.getStatus());
    assertEquals(0, member.getRecurringRetryCount());
    verify(gateway, times(1)).chargeWithToken(any(), anyString());
  }

  @Test
  void definiteFailureConsumesARetryAndWaitsADay() {
    when(gateway.chargeWithToken(any(), anyString()))
        .thenReturn(GatewayChargeResponse.builder().success(false).failureCode("DECLINED").build());

    service.tryAutoCharge(3L);
    service.tryAutoCharge(3L);

    assertEquals(1, member.getRecurringRetryCount());
    assertEquals(LocalDateTime.ofInstant(NOW, ZONE).plusDays(1), member.getRecurringNextRetryAt());
    verify(gateway, times(1)).chargeWithToken(any(), anyString());
  }

  @Test
  void disabledLiveMoneyGateBlocksAutoCharges() {
    org.mockito.Mockito.doThrow(new IllegalStateException("disabled"))
        .when(liveMoneyGuard)
        .requireEnabledForNewCharge();

    service.tryAutoCharge(3L);

    verify(gateway, never()).chargeWithToken(any(), anyString());
  }
}
