package kz.hrms.splitupauth.service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import kz.hrms.splitupauth.dto.FinanceRefundDto;
import kz.hrms.splitupauth.dto.FinanceTransactionDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only feeder for the admin "Финансы" drill-down page. Backs the four Finance KPI cards on the
 * dashboard with row-level transaction / refund listings so the operator can answer "who paid what,
 * when, for which room" without touching the DB.
 */
@Service
@RequiredArgsConstructor
public class AdminFinanceService {

  private final PaymentTransactionRepository paymentTransactionRepository;
  private final RefundTransactionRepository refundTransactionRepository;

  private static final int MAX_PAGE_SIZE = 100;

  @Transactional(readOnly = true)
  public PagedResponse<FinanceTransactionDto> listTransactions(
      String typeRaw,
      String statusRaw,
      LocalDateTime dateFrom,
      LocalDateTime dateTo,
      int page,
      int size) {
    PaymentTransactionType type = parseTxType(typeRaw);
    PaymentTransactionStatus status = parseTxStatus(statusRaw);

    Pageable pageable =
        PageRequest.of(Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));

    Specification<PaymentTransaction> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (type != null) predicates.add(cb.equal(root.get("type"), type));
          if (status != null) predicates.add(cb.equal(root.get("status"), status));
          if (dateFrom != null)
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
          if (dateTo != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
          return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

    Page<PaymentTransaction> result = paymentTransactionRepository.findAll(spec, pageable);
    List<FinanceTransactionDto> items = result.getContent().stream().map(this::toTxDto).toList();
    return toPagedResponse(result, items);
  }

  @Transactional(readOnly = true)
  public PagedResponse<FinanceRefundDto> listRefunds(
      String statusRaw, LocalDateTime dateFrom, LocalDateTime dateTo, int page, int size) {
    RefundStatus status = parseRefundStatus(statusRaw);

    Pageable pageable =
        PageRequest.of(Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));

    Specification<RefundTransaction> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (status != null) predicates.add(cb.equal(root.get("status"), status));
          if (dateFrom != null)
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
          if (dateTo != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
          return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

    Page<RefundTransaction> result = refundTransactionRepository.findAll(spec, pageable);
    List<FinanceRefundDto> items = result.getContent().stream().map(this::toRefundDto).toList();
    return toPagedResponse(result, items);
  }

  // ---------------------- mappers ----------------------

  private FinanceTransactionDto toTxDto(PaymentTransaction t) {
    Room room = t.getRoom();
    User owner = room != null ? room.getOwner() : null;

    // Payer: prefer the RoomMember's user (that's who joined the room); fall back
    // to the PaymentIntent.user when the transaction happens outside a member row
    // (rare — reconciliation edge cases).
    RoomMember member = t.getRoomMember();
    User payer = null;
    if (member != null && member.getUser() != null) {
      payer = member.getUser();
    } else {
      PaymentIntent intent = t.getPaymentIntent();
      if (intent != null && intent.getUser() != null) {
        payer = intent.getUser();
      }
    }

    return FinanceTransactionDto.builder()
        .id(t.getId())
        .createdAt(t.getCreatedAt())
        .type(t.getType() != null ? t.getType().name() : null)
        .status(t.getStatus() != null ? t.getStatus().name() : null)
        .amount(t.getAmount())
        .currency(t.getCurrency())
        .roomId(room != null ? room.getId() : null)
        .roomTitle(room != null ? room.getTitle() : null)
        .ownerUserId(owner != null ? owner.getId() : null)
        .ownerDisplayName(owner != null ? owner.getDisplayName() : null)
        .payerUserId(payer != null ? payer.getId() : null)
        .payerDisplayName(payer != null ? payer.getDisplayName() : null)
        .providerName(t.getProviderName())
        .cardPanMask(t.getCardPanMask())
        .reason(t.getReason())
        .failureMessage(t.getFailureMessage())
        .build();
  }

  private FinanceRefundDto toRefundDto(RefundTransaction r) {
    PaymentTransaction tx = r.getPaymentTransaction();
    Room room = tx != null ? tx.getRoom() : null;

    User admin = r.getAdminUser();

    // The "member" a refund is being issued to = the room member on the underlying
    // payment transaction (fallback: the paying user on the intent).
    User memberUser = null;
    if (tx != null) {
      RoomMember rm = tx.getRoomMember();
      if (rm != null && rm.getUser() != null) {
        memberUser = rm.getUser();
      } else if (tx.getPaymentIntent() != null && tx.getPaymentIntent().getUser() != null) {
        memberUser = tx.getPaymentIntent().getUser();
      }
    }

    return FinanceRefundDto.builder()
        .id(r.getId())
        .createdAt(r.getCreatedAt())
        .status(r.getStatus() != null ? r.getStatus().name() : null)
        .amount(r.getAmount())
        .currency(r.getCurrency())
        .reason(r.getReason())
        .adminUserId(admin != null ? admin.getId() : null)
        .adminDisplayName(admin != null ? admin.getDisplayName() : null)
        .paymentTransactionId(tx != null ? tx.getId() : null)
        .roomId(room != null ? room.getId() : null)
        .roomTitle(room != null ? room.getTitle() : null)
        .memberUserId(memberUser != null ? memberUser.getId() : null)
        .memberDisplayName(memberUser != null ? memberUser.getDisplayName() : null)
        .disputeId(r.getDispute() != null ? r.getDispute().getId() : null)
        .build();
  }

  // ---------------------- helpers ----------------------

  private int clampSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private PaymentTransactionType parseTxType(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return PaymentTransactionType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new InvalidRequestException("Unsupported transaction type: " + raw);
    }
  }

  private PaymentTransactionStatus parseTxStatus(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return PaymentTransactionStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new InvalidRequestException("Unsupported transaction status: " + raw);
    }
  }

  private RefundStatus parseRefundStatus(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return RefundStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new InvalidRequestException("Unsupported refund status: " + raw);
    }
  }

  private <T, R> PagedResponse<R> toPagedResponse(Page<T> source, List<R> items) {
    return PagedResponse.<R>builder()
        .items(items)
        .page(source.getNumber())
        .size(source.getSize())
        .totalItems(source.getTotalElements())
        .totalPages(source.getTotalPages())
        .hasNext(source.hasNext())
        .hasPrevious(source.hasPrevious())
        .build();
  }
}
