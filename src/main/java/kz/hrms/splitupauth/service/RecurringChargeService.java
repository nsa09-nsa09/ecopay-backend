package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SavedCard;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily scheduler for monthly subscription auto-charges.
 *
 * Picks ACTIVE members in MONTHLY rooms whose next billing date is within
 * the lead window, attempts a recurring charge through the saved default
 * card, and creates a new PaymentIntent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringChargeService {

    private final RoomMemberRepository roomMemberRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final SavedCardRepository savedCardRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentEventLogger eventLogger;
    private final PaymentService paymentService;

    /** Runs every day at 03:30 server time. */
    @Scheduled(cron = "0 30 3 * * *")
    public void runDailyAutoCharges() {
        log.info("RecurringChargeService: starting daily run");
        List<RoomMember> activeMembers = roomMemberRepository
                .findByStatusAndDeletedAtIsNull(MemberStatus.ACTIVE);
        for (RoomMember member : activeMembers) {
            try {
                tryAutoCharge(member.getId());
            } catch (Exception ex) {
                log.warn("Auto-charge failed for member {}: {}", member.getId(), ex.getMessage());
            }
        }
        log.info("RecurringChargeService: done, scanned {} active members", activeMembers.size());
    }

    @Transactional
    public void tryAutoCharge(Long memberId) {
        RoomMember member = roomMemberRepository.findById(memberId).orElse(null);
        if (member == null || member.getStatus() != MemberStatus.ACTIVE) return;

        var room = member.getRoom();
        if (room == null) return;
        if (room.getPeriodType() != PeriodType.MONTHLY) return;

        // Find latest successful charge — assume it covers a 30-day period.
        PaymentIntent lastSuccess = paymentIntentRepository
                .findFirstByRoomMemberOrderByCreatedAtDesc(member)
                .filter(pi -> pi.getStatus() == PaymentIntentStatus.SUCCESS)
                .orElse(null);
        if (lastSuccess == null) return;

        LocalDateTime nextBilling = lastSuccess.getCreatedAt().plusDays(30);
        LocalDateTime now = LocalDateTime.now();
        // Charge 1-2 days before the next billing date.
        if (now.isBefore(nextBilling.minusDays(2)) || now.isAfter(nextBilling)) return;

        SavedCard card = savedCardRepository
                .findByUserAndIsDefaultTrueAndStatus(member.getUser(), SavedCardStatus.ACTIVE)
                .orElse(null);
        if (card == null) {
            log.info("Member {} has no default saved card, skipping auto-charge", memberId);
            return;
        }

        String idempotencyKey = "recurring-" + memberId + "-" + nextBilling.toLocalDate();
        if (paymentIntentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return; // already attempted this period
        }

        var gateway = gatewayRegistry.defaultGateway();
        PaymentIntent intent = PaymentIntent.builder()
                .idempotencyKey(idempotencyKey)
                .roomMember(member)
                .user(member.getUser())
                .amount(lastSuccess.getAmount())
                .status(PaymentIntentStatus.PENDING)
                .providerName(gateway.providerName())
                .saveCardRequested(false)
                .savedCard(card)
                .expiresAt(now.plusMinutes(30))
                .build();
        intent = paymentIntentRepository.save(intent);

        try {
            GatewayChargeResponse resp = gateway.chargeWithToken(
                    GatewayChargeRequest.builder()
                            .intentId(intent.getId())
                            .idempotencyKey(intent.getIdempotencyKey())
                            .amount(intent.getAmount())
                            .currency("KZT")
                            .description("Ecopay recurring " + member.getRoom().getTitle())
                            .userEmail(member.getUser().getEmail())
                            .userPhone(member.getUser().getPhone())
                            .build(),
                    card.getProviderToken()
            );

            if (resp.isSuccess()) {
                intent.setStatus(PaymentIntentStatus.SUCCESS);
                intent.setExternalPaymentId(resp.getExternalPaymentId());
                paymentIntentRepository.save(intent);
                // Record the transaction + create the owner payout for this renewal
                // (previously missing → owner was never paid for recurring periods).
                paymentService.applySuccessfulCharge(intent, null, null);
            } else {
                intent.setStatus(PaymentIntentStatus.FAILED);
                intent.setFailureCode(resp.getFailureCode());
                intent.setFailureMessage(resp.getFailureMessage());
                if ("EXPIRED_CARD".equalsIgnoreCase(resp.getFailureCode())
                        || (resp.getFailureMessage() != null
                                && resp.getFailureMessage().toLowerCase().contains("expired"))) {
                    card.setStatus(SavedCardStatus.EXPIRED);
                    savedCardRepository.save(card);
                }
            }
            paymentIntentRepository.save(intent);

            eventLogger.log("INTENT", intent.getId(),
                    intent.getStatus() == PaymentIntentStatus.SUCCESS
                            ? "RECURRING_SUCCESS" : "RECURRING_FAILED",
                    "PENDING", intent.getStatus().name(),
                    null, null, idempotencyKey,
                    java.util.Map.of("memberId", String.valueOf(memberId)));
        } catch (Exception ex) {
            log.error("Recurring charge call failed for member {}: {}", memberId, ex.getMessage());
            intent.setStatus(PaymentIntentStatus.FAILED);
            intent.setFailureMessage(ex.getMessage());
            paymentIntentRepository.save(intent);
        }
    }
}
