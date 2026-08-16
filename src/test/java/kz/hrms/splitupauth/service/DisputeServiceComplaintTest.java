package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.dto.ApplyDisputeSanctionsRequest;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.CreateRoomComplaintRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.DisputeStatus;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomEventLog;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import kz.hrms.splitupauth.repository.RoomEventLogRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Covers the member complaint -> admin case -> confirmed owner breach refund path without Docker. */
@ExtendWith(MockitoExtension.class)
class DisputeServiceComplaintTest {

  @Mock private DisputeRepository disputeRepository;
  @Mock private SupportTicketRepository supportTicketRepository;
  @Mock private AdminActionLogRepository adminActionLogRepository;
  @Mock private RoomEventLogRepository roomEventLogRepository;
  @Mock private RoomRepository roomRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private RefundTransactionRepository refundTransactionRepository;
  @Mock private UserRepository userRepository;
  @Mock private RefundService refundService;
  @Mock private ReputationService reputationService;
  @Mock private NotificationService notificationService;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PaymentGateway paymentGateway;

  private DisputeService service;

  @BeforeEach
  void setUp() {
    service =
        new DisputeService(
            disputeRepository,
            supportTicketRepository,
            adminActionLogRepository,
            roomEventLogRepository,
            roomRepository,
            roomMemberRepository,
            paymentTransactionRepository,
            refundTransactionRepository,
            userRepository,
            refundService,
            reputationService,
            notificationService);
  }

  @Test
  void openMemberComplaint_paidMemberCreatesCaseAndStopsAutoActivation() {
    User owner = user(10L, Role.USER);
    User memberUser = user(11L, Role.USER);
    Room room = Room.builder().id(40L).owner(owner).status(RoomStatus.OPEN).build();
    RoomMember member =
        RoomMember.builder()
            .id(50L)
            .room(room)
            .user(memberUser)
            .status(MemberStatus.PENDING)
            .requiresAdminReview(false)
            .build();
    CreateRoomComplaintRequest request = new CreateRoomComplaintRequest();
    request.setReasonCode("access_not_provided");
    request.setDescription("Owner has not provided the promised subscription access.");

    when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, memberUser))
        .thenReturn(Optional.of(member));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            member.getId(), PaymentTransactionStatus.SUCCESS))
        .thenReturn(true);
    when(disputeRepository.existsByRoomMemberAndStatusIn(eq(member), any())).thenReturn(false);
    when(disputeRepository.save(any(Dispute.class)))
        .thenAnswer(
            invocation -> {
              Dispute dispute = invocation.getArgument(0);
              dispute.setId(60L);
              return dispute;
            });

    service.openMemberComplaint(room.getId(), memberUser, request);

    ArgumentCaptor<Dispute> caseCaptor = ArgumentCaptor.forClass(Dispute.class);
    verify(disputeRepository).save(caseCaptor.capture());
    Dispute created = caseCaptor.getValue();
    assertEquals(room, created.getRoom());
    assertEquals(member, created.getRoomMember());
    assertEquals(memberUser, created.getOpenedByUser());
    assertEquals("ACCESS_NOT_PROVIDED", created.getReasonCode());
    assertEquals(DisputeStatus.OPEN, created.getStatus());
    assertEquals(Boolean.TRUE, member.getRequiresAdminReview());
    verify(roomMemberRepository).save(member);
  }

  @Test
  void openMemberComplaint_rejectsSecondOpenCaseForSameMembership() {
    User owner = user(10L, Role.USER);
    User memberUser = user(11L, Role.USER);
    Room room = Room.builder().id(40L).owner(owner).status(RoomStatus.OPEN).build();
    RoomMember member =
        RoomMember.builder()
            .id(50L)
            .room(room)
            .user(memberUser)
            .status(MemberStatus.ACTIVE)
            .build();
    CreateRoomComplaintRequest request = new CreateRoomComplaintRequest();
    request.setReasonCode("OTHER");
    request.setDescription("The owner has repeatedly failed to meet the agreed obligations.");

    when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, memberUser))
        .thenReturn(Optional.of(member));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            member.getId(), PaymentTransactionStatus.SUCCESS))
        .thenReturn(true);
    when(disputeRepository.existsByRoomMemberAndStatusIn(eq(member), any())).thenReturn(true);

    assertThrows(
        InvalidRequestException.class,
        () -> service.openMemberComplaint(room.getId(), memberUser, request));

    verify(disputeRepository, never()).save(any());
    verify(roomMemberRepository, never()).save(any());
  }

  @Test
  void confirmedOwnerViolation_refundsEveryPaidMemberAndClosesCase() {
    User owner = user(10L, Role.USER);
    User admin = user(1L, Role.ADMIN);
    User claimant = user(11L, Role.USER);
    Room room = Room.builder().id(40L).owner(owner).status(RoomStatus.ACTIVE).build();
    RoomMember claimantMember =
        RoomMember.builder().id(50L).room(room).user(claimant).status(MemberStatus.PENDING).build();
    Dispute dispute =
        Dispute.builder()
            .id(60L)
            .room(room)
            .roomMember(claimantMember)
            .openedByUser(claimant)
            .status(DisputeStatus.OPEN)
            .build();
    PaymentTransaction first =
        PaymentTransaction.builder().id(70L).room(room).amount(new BigDecimal("1500.00")).build();
    PaymentTransaction second =
        PaymentTransaction.builder().id(71L).room(room).amount(new BigDecimal("2000.00")).build();
    ApplyDisputeSanctionsRequest request = new ApplyDisputeSanctionsRequest();
    request.setReason("Owner did not provide the paid service to room members.");
    HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);

    when(disputeRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
    when(paymentTransactionRepository.findByRoom_IdAndStatusAndTypeOrderByCreatedAtAsc(
            room.getId(), PaymentTransactionStatus.SUCCESS, PaymentTransactionType.CHARGE))
        .thenReturn(List.of(first, second));
    when(refundTransactionRepository.sumActiveRefundAmounts(first)).thenReturn(BigDecimal.ZERO);
    when(refundTransactionRepository.sumActiveRefundAmounts(second)).thenReturn(BigDecimal.ZERO);

    service.applyOwnerViolationSanctions(dispute.getId(), admin, request, httpRequest);

    ArgumentCaptor<CreateRefundRequest> refundCaptor =
        ArgumentCaptor.forClass(CreateRefundRequest.class);
    verify(refundService, org.mockito.Mockito.times(2))
        .createRefund(eq(admin), refundCaptor.capture(), eq(httpRequest));
    assertEquals(List.of(70L, 71L), refundCaptor.getAllValues().stream().map(CreateRefundRequest::getPaymentTransactionId).toList());
    assertEquals(
        List.of(new BigDecimal("1500.00"), new BigDecimal("2000.00")),
        refundCaptor.getAllValues().stream().map(CreateRefundRequest::getAmount).toList());
    assertEquals(RoomStatus.BLOCKED, room.getStatus());
    assertEquals(UserStatus.BANNED, owner.getStatus());
    assertEquals(DisputeStatus.RESOLVED, dispute.getStatus());
    assertEquals("OWNER_VIOLATION_CONFIRMED", dispute.getDecision());
    verify(roomRepository).save(room);
    verify(userRepository).save(owner);
    verify(disputeRepository).save(dispute);
    verify(adminActionLogRepository, org.mockito.Mockito.times(2)).save(any(AdminActionLog.class));
    verify(roomEventLogRepository).save(any(RoomEventLog.class));
  }

  @Test
  void confirmedOwnerViolation_rejectsAlreadyClosedCaseWithoutRefund() {
    User admin = user(1L, Role.ADMIN);
    User owner = user(10L, Role.USER);
    Room room = Room.builder().id(40L).owner(owner).status(RoomStatus.BLOCKED).build();
    Dispute dispute =
        Dispute.builder()
            .id(60L)
            .room(room)
            .openedByUser(user(11L, Role.USER))
            .status(DisputeStatus.RESOLVED)
            .build();
    ApplyDisputeSanctionsRequest request = new ApplyDisputeSanctionsRequest();
    request.setReason("Already handled.");

    when(disputeRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));

    assertThrows(
        InvalidRequestException.class,
        () ->
            service.applyOwnerViolationSanctions(
                dispute.getId(), admin, request, org.mockito.Mockito.mock(HttpServletRequest.class)));

    verify(refundService, never()).createRefund(any(), any(), any());
    verify(roomRepository, never()).save(any());
  }

  @Test
  void adminRefund_workerDispatchesToGatewayAndFinalizesSuccessfulCharge() {
    PlatformTransactionManager transactionManager =
        org.mockito.Mockito.mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    RefundService refundService =
        new RefundService(
            refundTransactionRepository,
            paymentTransactionRepository,
            disputeRepository,
            adminActionLogRepository,
            gatewayRegistry,
            org.mockito.Mockito.mock(PaymentEventLogger.class),
            org.mockito.Mockito.mock(PayoutService.class),
            notificationService,
            org.mockito.Mockito.mock(MoneyLedgerService.class),
            Clock.systemUTC(),
            transactionManager);
    User admin = user(1L, Role.ADMIN);
    PaymentTransaction charge =
        PaymentTransaction.builder()
            .id(70L)
            .type(PaymentTransactionType.CHARGE)
            .status(PaymentTransactionStatus.SUCCESS)
            .amount(new BigDecimal("1500.00"))
            .currency("KZT")
            .externalTransactionId("provider-charge-70")
            .build();
    CreateRefundRequest request = new CreateRefundRequest();
    request.setPaymentTransactionId(charge.getId());
    request.setAmount(new BigDecimal("1500.00"));
    request.setReason("Confirmed owner violation");
    request.setIdempotencyKey("dispute-60-refund-70");
    HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);

    when(refundTransactionRepository.findByIdempotencyKey(request.getIdempotencyKey()))
        .thenReturn(Optional.empty());
    when(paymentTransactionRepository.findWithLockById(charge.getId())).thenReturn(Optional.of(charge));
    when(refundTransactionRepository.sumActiveRefundAmounts(charge))
        .thenReturn(BigDecimal.ZERO, new BigDecimal("1500.00"));
    java.util.concurrent.atomic.AtomicReference<kz.hrms.splitupauth.entity.RefundTransaction>
        savedRefund = new java.util.concurrent.atomic.AtomicReference<>();
    when(refundTransactionRepository.save(any()))
        .thenAnswer(
            invocation -> {
              var refund = invocation.getArgument(0, kz.hrms.splitupauth.entity.RefundTransaction.class);
              if (refund.getId() == null) {
                refund.setId(80L);
              }
              savedRefund.set(refund);
              return refund;
            });
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.refund(any(GatewayRefundRequest.class)))
        .thenReturn(GatewayRefundResponse.builder().success(true).externalRefundId("provider-refund-80").build());

    refundService.createRefund(admin, request, httpRequest);
    verify(paymentGateway, never()).refund(any());

    when(refundTransactionRepository.findDispatchableIds(any(), eq(3), any()))
        .thenReturn(List.of(80L));
    when(refundTransactionRepository.findWithLockById(80L))
        .thenAnswer(invocation -> Optional.of(savedRefund.get()));
    refundService.processPendingRefundsOnce(1);

    ArgumentCaptor<GatewayRefundRequest> gatewayRequest =
        ArgumentCaptor.forClass(GatewayRefundRequest.class);
    verify(paymentGateway).refund(gatewayRequest.capture());
    assertEquals("provider-charge-70", gatewayRequest.getValue().getExternalPaymentId());
    assertEquals(new BigDecimal("1500.00"), gatewayRequest.getValue().getAmount());
    assertEquals(PaymentTransactionStatus.REFUNDED_FULL, charge.getStatus());
    verify(paymentTransactionRepository).save(charge);
  }

  private static User user(Long id, Role role) {
    return User.builder().id(id).role(role).status(UserStatus.ACTIVE).displayName("User " + id).build();
  }
}
