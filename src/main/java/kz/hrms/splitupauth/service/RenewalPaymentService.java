package kz.hrms.splitupauth.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.MemberBillingDto;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RenewalIntentRequest;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentPurpose;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual renewal of a membership for its next billing period, through the same hosted FreedomPay
 * page as the first payment (redirect, no stored card, no auto-charge). This closes the business
 * gap where a member paid once and then never again: from the second period on the owner would get
 * nothing and the platform no commission.
 *
 * <p>Kept separate from {@link PaymentService} so that class is not bloated; the actual gateway
 * call reuses {@link PaymentService#initGatewayForPreparedIntent} verbatim, and the amount comes
 * from {@link PaymentService#currentChargeBreakdown} (same math as the first payment, so a price
 * change applies from the next cycle).
 */
@Service
@Slf4j
public class RenewalPaymentService {

  private static final List<PaymentIntentStatus> OPEN_STATUSES =
      List.of(
          PaymentIntentStatus.PENDING,
          PaymentIntentStatus.UNKNOWN,
          PaymentIntentStatus.RECONCILING);

  private static final List<PaymentIntentStatus> CAPTURED_STATUSES =
      List.of(
          PaymentIntentStatus.SUCCESS,
          PaymentIntentStatus.REFUND_REQUIRED,
          PaymentIntentStatus.REFUND_PENDING,
          PaymentIntentStatus.REFUNDED,
          PaymentIntentStatus.REQUIRES_REVIEW,
          PaymentIntentStatus.CAPTURE_ANOMALY);

  private final RoomMemberRepository roomMemberRepository;
  private final PaymentIntentRepository paymentIntentRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PaymentService paymentService;
  private final LiveMoneyGuard liveMoneyGuard;
  private final PaymentEventLogger eventLogger;
  private final Clock clock;
  private final TransactionTemplate tx;

  @Value("${app.renewal.window-days:5}")
  private long windowDays;

  @Value("${app.renewal.grace-days:3}")
  private long graceDays;

  public RenewalPaymentService(
      RoomMemberRepository roomMemberRepository,
      PaymentIntentRepository paymentIntentRepository,
      PaymentGatewayRegistry gatewayRegistry,
      PaymentService paymentService,
      LiveMoneyGuard liveMoneyGuard,
      PaymentEventLogger eventLogger,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.roomMemberRepository = roomMemberRepository;
    this.paymentIntentRepository = paymentIntentRepository;
    this.gatewayRegistry = gatewayRegistry;
    this.paymentService = paymentService;
    this.liveMoneyGuard = liveMoneyGuard;
    this.eventLogger = eventLogger;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Creates (or idempotently returns) a renewal payment intent for the member's next period. */
  public PaymentIntentResponse createRenewalIntent(
      Long roomMemberId, User user, RenewalIntentRequest request) {
    liveMoneyGuard.requireEnabledForNewCharge();

    PreparedRenewal prepared;
    try {
      prepared = tx.execute(status -> prepare(roomMemberId, user, request));
    } catch (DataIntegrityViolationException race) {
      // Concurrent insert with the same idempotency key: re-read and return it.
      PaymentIntent existing =
          paymentIntentRepository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
      if (existing != null) {
        return paymentService.toResponse(existing);
      }
      throw race;
    }

    if (prepared.existingResponse() != null) {
      return prepared.existingResponse();
    }
    return paymentService.initGatewayForPreparedIntent(
        prepared.intent(), null, prepared.chargeRequest(), null, user.getId());
  }

  private PreparedRenewal prepare(Long roomMemberId, User user, RenewalIntentRequest request) {
    RoomMember member =
        roomMemberRepository
            .findWithLockById(roomMemberId)
            .filter(m -> m.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

    if (member.getUser() == null || !member.getUser().getId().equals(user.getId())) {
      throw new ForbiddenOperationException("You can only renew your own membership");
    }

    // Idempotency first, so a retried call with the same key always returns the same intent.
    PaymentIntent sameKey =
        paymentIntentRepository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
    if (sameKey != null) {
      return PreparedRenewal.existing(requireSameRenewalRequest(sameKey, roomMemberId, user));
    }

    Room room = member.getRoom();
    if (member.getStatus() != MemberStatus.ACTIVE
        || member.getUser().getStatus() != UserStatus.ACTIVE
        || room == null
        || room.getDeletedAt() != null
        || room.getStatus() != RoomStatus.ACTIVE) {
      throw new InvalidRequestException("RENEWAL_NOT_OPEN", "Renewal is not available right now");
    }
    if (!BillingSchedule.isSupported(room.getPeriodType())) {
      throw new InvalidRequestException(
          "RENEWAL_NOT_SUPPORTED", "This plan's period does not support renewal");
    }

    LocalDateTime period = member.getNextBillingAt();
    LocalDateTime now = LocalDateTime.now(clock);
    if (period == null
        || now.isBefore(period.minusDays(windowDays))
        || now.isAfter(period.plusDays(graceDays))) {
      throw new InvalidRequestException(
          "RENEWAL_NOT_OPEN", "The renewal window for this period is not open");
    }

    List<PaymentIntent> forPeriod =
        paymentIntentRepository
            .findByRoomMember_IdAndBillingPeriodStartAndStatusInOrderByCreatedAtDesc(
                roomMemberId, period, union(OPEN_STATUSES, CAPTURED_STATUSES));
    PaymentIntent captured =
        forPeriod.stream()
            .filter(p -> CAPTURED_STATUSES.contains(p.getStatus()))
            .findFirst()
            .orElse(null);
    if (captured != null) {
      throw new ResourceConflictException(
          "RENEWAL_ALREADY_PAID", "This period has already been paid");
    }
    PaymentIntent open =
        forPeriod.stream()
            .filter(p -> OPEN_STATUSES.contains(p.getStatus()))
            .findFirst()
            .orElse(null);
    if (open != null) {
      return PreparedRenewal.existing(paymentService.toResponse(open));
    }

    PaymentGateway gateway = gatewayRegistry.defaultGateway();
    PaymentService.ChargeBreakdown breakdown = paymentService.currentChargeBreakdown(room);
    PaymentIntent intent =
        paymentIntentRepository.save(
            PaymentIntent.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .roomMember(member)
                .user(member.getUser())
                .amount(breakdown.amount())
                .commissionAmount(breakdown.commission())
                .status(PaymentIntentStatus.PENDING)
                .providerName(gateway.providerName())
                .saveCardRequested(false)
                .purpose(PaymentIntentPurpose.RENEWAL)
                .billingPeriodStart(period)
                .expiresAt(now.plusMinutes(30))
                .build());

    eventLogger.log(
        "INTENT",
        intent.getId(),
        "RENEWAL_CREATED",
        null,
        intent.getStatus().name(),
        user.getId(),
        null,
        intent.getIdempotencyKey(),
        java.util.Map.of(
            "roomMemberId",
            String.valueOf(roomMemberId),
            "billingPeriodStart",
            String.valueOf(period),
            "amount",
            String.valueOf(intent.getAmount())));

    GatewayChargeRequest chargeRequest =
        GatewayChargeRequest.builder()
            .intentId(intent.getId())
            .roomMemberId(member.getId())
            .roomId(room.getId())
            .idempotencyKey(intent.getIdempotencyKey())
            .amount(intent.getAmount())
            .currency("KZT")
            .description("EcoPay renewal #" + member.getId())
            .userEmail(member.getUser().getEmail())
            .userPhone(member.getUser().getPhone())
            .userId(
                member.getUser().getId() == null ? null : String.valueOf(member.getUser().getId()))
            .build();
    return PreparedRenewal.fresh(intent, chargeRequest);
  }

  private PaymentIntentResponse requireSameRenewalRequest(
      PaymentIntent existing, Long roomMemberId, User user) {
    boolean sameUser =
        existing.getUser() != null && existing.getUser().getId().equals(user.getId());
    boolean sameMember =
        existing.getRoomMember() != null && existing.getRoomMember().getId().equals(roomMemberId);
    if (!sameUser || !sameMember || existing.getPurpose() != PaymentIntentPurpose.RENEWAL) {
      throw new ResourceConflictException(
          "IDEMPOTENCY_KEY_CONFLICT", "Idempotency key belongs to a different payment request");
    }
    return paymentService.toResponse(existing);
  }

  /**
   * Renewal/billing state for the member's current period, or null when the member is not ACTIVE or
   * the plan has no renewable period. Read-only.
   */
  @Transactional(readOnly = true)
  public MemberBillingDto describeBilling(Long roomMemberId, User user) {
    RoomMember member = roomMemberRepository.findById(roomMemberId).orElse(null);
    if (member == null
        || member.getDeletedAt() != null
        || member.getUser() == null
        || !member.getUser().getId().equals(user.getId())
        || member.getStatus() != MemberStatus.ACTIVE) {
      return null;
    }
    Room room = member.getRoom();
    if (room == null || !BillingSchedule.isSupported(room.getPeriodType())) {
      return null;
    }

    LocalDateTime period = member.getNextBillingAt();
    LocalDateTime now = LocalDateTime.now(clock);
    boolean periodPaid = period != null && hasCapturedForPeriod(roomMemberId, period);
    boolean withinWindow =
        period != null
            && !now.isBefore(period.minusDays(windowDays))
            && !now.isAfter(period.plusDays(graceDays));
    boolean renewalOpen = withinWindow && !periodPaid;
    boolean overdue = period != null && now.isAfter(period) && !periodPaid;

    MemberBillingDto.MemberBillingDtoBuilder dto =
        MemberBillingDto.builder().nextBillingAt(period).renewalOpen(renewalOpen).overdue(overdue);
    if (renewalOpen) {
      PaymentService.ChargeBreakdown breakdown = paymentService.currentChargeBreakdown(room);
      dto.renewalAmountKzt(breakdown.amount())
          .renewalShareKzt(breakdown.share())
          .renewalCommissionKzt(breakdown.commission());
    }
    return dto.build();
  }

  private boolean hasCapturedForPeriod(Long roomMemberId, LocalDateTime period) {
    return !paymentIntentRepository
        .findByRoomMember_IdAndBillingPeriodStartAndStatusInOrderByCreatedAtDesc(
            roomMemberId, period, CAPTURED_STATUSES)
        .isEmpty();
  }

  private static List<PaymentIntentStatus> union(
      List<PaymentIntentStatus> a, List<PaymentIntentStatus> b) {
    List<PaymentIntentStatus> all = new java.util.ArrayList<>(a);
    all.addAll(b);
    return all;
  }

  private record PreparedRenewal(
      PaymentIntent intent,
      GatewayChargeRequest chargeRequest,
      PaymentIntentResponse existingResponse) {
    static PreparedRenewal fresh(PaymentIntent intent, GatewayChargeRequest chargeRequest) {
      return new PreparedRenewal(intent, chargeRequest, null);
    }

    static PreparedRenewal existing(PaymentIntentResponse response) {
      return new PreparedRenewal(null, null, response);
    }
  }

  // Package-private accessors for tests / schedulers that reason about the renewal window.
  long windowDays() {
    return windowDays;
  }

  long graceDays() {
    return graceDays;
  }
}
