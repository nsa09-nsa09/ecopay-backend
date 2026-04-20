package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.hrms.splitupauth.dto.ConfirmPaymentRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final int MONEY_SCALE = 2;

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomMemberService roomMemberService;

    @Transactional
    public PaymentIntentResponse createPaymentIntent(
            Long roomMemberId,
            User currentUser,
            CreatePaymentIntentRequest request
    ) {
        RoomMember roomMember = roomMemberRepository.findById(roomMemberId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

        if (roomMember.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Membership not found");
        }

        if (!roomMember.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("You can only create payment intent for your own membership");
        }

        if (roomMember.getStatus() != MemberStatus.APPLIED) {
            throw new InvalidRequestException("Payment intent can only be created for APPLIED membership");
        }

        PaymentIntent existing = paymentIntentRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .orElse(null);

        if (existing != null) {
            return mapToResponse(existing);
        }

        PaymentIntent intent = PaymentIntent.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .roomMember(roomMember)
                .user(currentUser)
                .amount(resolvePaymentAmount(roomMember.getRoom()))
                .status(PaymentIntentStatus.PENDING)
                .providerName("STUB")
                .build();

        intent = paymentIntentRepository.save(intent);

        return mapToResponse(intent);
    }

    @Transactional
    public PaymentIntentResponse confirmPaymentSuccess(
            Long paymentIntentId,
            User currentUser,
            ConfirmPaymentRequest request
    ) {
        PaymentIntent paymentIntent = paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));

        if (!paymentIntent.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("You can only confirm your own payment intent");
        }

        if (paymentIntent.getStatus() == PaymentIntentStatus.SUCCESS) {
            return mapToResponse(paymentIntent);
        }

        if (paymentIntent.getStatus() != PaymentIntentStatus.PENDING) {
            throw new InvalidRequestException("Only PENDING payment intent can be confirmed");
        }

        paymentIntent.setStatus(PaymentIntentStatus.SUCCESS);
        paymentIntent.setExternalPaymentId(request.getExternalTransactionId());
        paymentIntent = paymentIntentRepository.save(paymentIntent);

        ObjectNode rawPayload = JsonNodeFactory.instance.objectNode();
        rawPayload.put("provider", "STUB");
        rawPayload.put("paymentIntentId", paymentIntent.getId());
        rawPayload.put("roomMemberId", paymentIntent.getRoomMember().getId());
        rawPayload.put("externalTransactionId", request.getExternalTransactionId());

        PaymentTransaction tx = PaymentTransaction.builder()
                .paymentIntent(paymentIntent)
                .room(paymentIntent.getRoomMember().getRoom())
                .roomMember(paymentIntent.getRoomMember())
                .type(PaymentTransactionType.CHARGE)
                .externalTransactionId(request.getExternalTransactionId())
                .amount(paymentIntent.getAmount())
                .currency("KZT")
                .status(PaymentTransactionStatus.SUCCESS)
                .providerName("STUB")
                .rawPayload(rawPayload)
                .build();

        paymentTransactionRepository.save(tx);

        roomMemberService.markMembershipAsPaid(paymentIntent.getRoomMember());

        return mapToResponse(paymentIntent);
    }

    private PaymentIntentResponse mapToResponse(PaymentIntent intent) {
        return PaymentIntentResponse.builder()
                .id(intent.getId())
                .idempotencyKey(intent.getIdempotencyKey())
                .amount(intent.getAmount())
                .status(intent.getStatus())
                .providerName(intent.getProviderName())
                .externalPaymentId(intent.getExternalPaymentId())
                .roomMemberId(intent.getRoomMember().getId())
                .build();
    }

    private BigDecimal resolvePaymentAmount(Room room) {
        if (room == null) {
            throw new InvalidRequestException("Room configuration is required to calculate payment amount");
        }

        if (isPositiveAmount(room.getPricePerMember())) {
            return normalizeMoneyAmount(
                    room.getPricePerMember(),
                    "Room pricePerMember"
            );
        }

        if (!isPositiveAmount(room.getPriceTotal())) {
            throw new InvalidRequestException(
                    "Cannot determine payment amount: room must have positive pricePerMember or positive priceTotal"
            );
        }

        int participantCount = resolveParticipantCount(room);

        try {
            BigDecimal share = room.getPriceTotal().divide(
                    BigDecimal.valueOf(participantCount),
                    MONEY_SCALE,
                    RoundingMode.UNNECESSARY
            );
            return normalizeMoneyAmount(
                    share,
                    "Calculated payment amount"
            );
        } catch (ArithmeticException ex) {
            throw new InvalidRequestException(
                    "Cannot determine payment amount: priceTotal cannot be split across member slots without rounding"
            );
        }
    }

    private int resolveParticipantCount(Room room) {
        Integer maxMembers = room.getMaxMembers();
        if (maxMembers == null || maxMembers < 2) {
            throw new InvalidRequestException(
                    "Cannot determine payment amount: room maxMembers must be at least 2"
            );
        }

        return maxMembers;
    }

    private BigDecimal normalizeMoneyAmount(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new InvalidRequestException(fieldName + " must not be null");
        }

        if (amount.signum() <= 0) {
            throw new InvalidRequestException(fieldName + " must be greater than 0");
        }

        try {
            return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new InvalidRequestException(fieldName + " must have at most 2 decimal places");
        }
    }

    private boolean isPositiveAmount(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
