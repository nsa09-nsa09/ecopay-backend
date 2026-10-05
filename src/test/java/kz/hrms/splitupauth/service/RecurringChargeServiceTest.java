package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Block 5b: a recurring auto-charge must recompute amount + commission from the room's CURRENT price
 * (via {@link PaymentService#currentChargeBreakdown}), not copy them from the last successful
 * payment — so an owner price change or a commission-tier change takes effect on the next cycle.
 */
@ExtendWith(MockitoExtension.class)
class RecurringChargeServiceTest {

  @Mock RoomMemberRepository roomMemberRepository;
  @Mock PaymentIntentRepository paymentIntentRepository;
  @Mock SavedCardRepository savedCardRepository;
  @Mock PaymentGatewayRegistry gatewayRegistry;
  @Mock PaymentEventLogger eventLogger;
  @Mock PaymentService paymentService;
  @Mock PaymentGateway gateway;
  @Mock kz.hrms.splitupauth.scheduler.SchedulerLock schedulerLock;

  private RecurringChargeService service;

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
            schedulerLock);
    ReflectionTestUtils.setField(service, "recurringEnabled", true);
  }

  @Test
  void recurringChargeUsesCurrentRoomPrice_notLastSuccessAmount() {
    LocalDateTime now = LocalDateTime.now();

    User user = User.builder().id(1L).status(UserStatus.ACTIVE).build();
    Room room = Room.builder().id(10L).periodType(PeriodType.MONTHLY).title("Netflix").build();
    RoomMember member =
        RoomMember.builder()
            .id(20L)
            .status(MemberStatus.ACTIVE)
            .user(user)
            .room(room)
            .billingAnchorAt(now.minusMonths(1))
            .billingPeriodStart(now.minusMonths(1))
            .nextBillingAt(now.minusDays(1)) // due now
            .recurringRetryCount(0)
            .build();

    // Last successful payment carried the OLD price.
    PaymentIntent lastSuccess =
        PaymentIntent.builder()
            .amount(new BigDecimal("2500.00"))
            .commissionAmount(new BigDecimal("500.00"))
            .createdAt(now.minusMonths(1))
            .build();

    SavedCard card =
        SavedCard.builder().providerToken("tok").status(SavedCardStatus.ACTIVE).build();

    when(roomMemberRepository.findWithLockById(20L)).thenReturn(Optional.of(member));
    when(paymentIntentRepository.findFirstByRoomMemberAndStatusOrderByCreatedAtDesc(
            member, PaymentIntentStatus.SUCCESS))
        .thenReturn(Optional.of(lastSuccess));
    when(paymentIntentRepository.findFirstByRoomMember_IdAndStatusInOrderByCreatedAtDesc(
            eq(20L), any()))
        .thenReturn(Optional.empty());
    when(savedCardRepository.findByUserAndIsDefaultTrueAndStatus(user, SavedCardStatus.ACTIVE))
        .thenReturn(Optional.of(card));
    when(paymentIntentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(gatewayRegistry.defaultGateway()).thenReturn(gateway);
    lenient().when(gateway.providerName()).thenReturn("mock");
    when(gateway.chargeWithToken(any(), eq("tok")))
        .thenReturn(
            GatewayChargeResponse.builder()
                .success(true)
                .externalPaymentId("ext")
                .providerStatusCode("ok")
                .build());

    // The room price changed since lastSuccess: new share 3000 + commission 700 = 3700.
    when(paymentService.currentChargeBreakdown(room))
        .thenReturn(
            new PaymentService.ChargeBreakdown(
                new BigDecimal("3000.00"), new BigDecimal("700.00"), new BigDecimal("3700.00")));

    when(paymentIntentRepository.save(any(PaymentIntent.class)))
        .thenAnswer(
            inv -> {
              PaymentIntent p = inv.getArgument(0);
              if (p.getId() == null) {
                p.setId(999L);
              }
              return p;
            });
    lenient().when(roomMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.tryAutoCharge(20L);

    ArgumentCaptor<PaymentIntent> captor = ArgumentCaptor.forClass(PaymentIntent.class);
    verify(paymentIntentRepository).save(captor.capture());
    PaymentIntent created = captor.getValue();

    assertEquals(
        0,
        new BigDecimal("3700.00").compareTo(created.getAmount()),
        "recurring charge must use the current room price, not lastSuccess amount (2500.00)");
    assertEquals(
        0,
        new BigDecimal("700.00").compareTo(created.getCommissionAmount()),
        "recurring charge must use the current commission, not lastSuccess commission (500.00)");
  }
}
