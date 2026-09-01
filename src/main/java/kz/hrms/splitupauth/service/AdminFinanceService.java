package kz.hrms.splitupauth.service;

import jakarta.persistence.criteria.Predicate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kz.hrms.splitupauth.dto.FinancePayoutDto;
import kz.hrms.splitupauth.dto.FinanceRefundDto;
import kz.hrms.splitupauth.dto.FinanceTransactionDto;
import kz.hrms.splitupauth.dto.FinanceWebhookDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
  private final PayoutRepository payoutRepository;
  private final NamedParameterJdbcTemplate jdbcTemplate;

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
        PageRequest.of(
            Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));

    Specification<PaymentTransaction> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (type != null) predicates.add(cb.equal(root.get("type"), type));
          if (status != null) predicates.add(cb.equal(root.get("status"), status));
          if (dateFrom != null)
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
          if (dateTo != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
          return predicates.isEmpty()
              ? cb.conjunction()
              : cb.and(predicates.toArray(new Predicate[0]));
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
        PageRequest.of(
            Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));

    Specification<RefundTransaction> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (status != null) predicates.add(cb.equal(root.get("status"), status));
          if (dateFrom != null)
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
          if (dateTo != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
          return predicates.isEmpty()
              ? cb.conjunction()
              : cb.and(predicates.toArray(new Predicate[0]));
        };

    Page<RefundTransaction> result = refundTransactionRepository.findAll(spec, pageable);
    List<FinanceRefundDto> items = result.getContent().stream().map(this::toRefundDto).toList();
    return toPagedResponse(result, items);
  }

  @Transactional(readOnly = true)
  public PagedResponse<FinancePayoutDto> listPayouts(
      String statusRaw, LocalDateTime dateFrom, LocalDateTime dateTo, int page, int size) {
    String status = normalizeStringFilter(statusRaw);

    Pageable pageable =
        PageRequest.of(
            Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));

    Specification<Payout> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (status != null) predicates.add(cb.equal(root.get("status"), status));
          if (dateFrom != null)
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
          if (dateTo != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
          return predicates.isEmpty()
              ? cb.conjunction()
              : cb.and(predicates.toArray(new Predicate[0]));
        };

    Page<Payout> result = payoutRepository.findAll(spec, pageable);
    List<FinancePayoutDto> items = result.getContent().stream().map(this::toPayoutDto).toList();
    return toPagedResponse(result, items);
  }

  @Transactional(readOnly = true)
  public PagedResponse<FinanceWebhookDto> listWebhooks(
      String statusRaw,
      String scriptRaw,
      LocalDateTime dateFrom,
      LocalDateTime dateTo,
      int page,
      int size) {
    String status = normalizeStringFilter(statusRaw);
    String script = normalizeRawFilter(scriptRaw);
    int pageNumber = Math.max(0, page);
    int pageSize = clampSize(size);
    Set<String> columns = webhookInboxColumns();
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("limit", pageSize).addValue("offset", pageNumber * pageSize);

    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (status != null) {
      where.append(" AND processing_status = :status");
      params.addValue("status", status);
    }
    if (script != null && columns.contains("callback_script")) {
      where.append(" AND callback_script = :script");
      params.addValue("script", script);
    }
    if (dateFrom != null) {
      where.append(" AND received_at >= :dateFrom");
      params.addValue("dateFrom", dateFrom);
    }
    if (dateTo != null) {
      where.append(" AND received_at <= :dateTo");
      params.addValue("dateTo", dateTo);
    }

    Long total =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM freedom_webhook_inbox" + where, params, Long.class);
    long totalItems = total == null ? 0L : total;

    String sql =
        "SELECT "
            + webhookSelect(columns)
            + " FROM freedom_webhook_inbox"
            + where
            + " ORDER BY received_at DESC, id DESC LIMIT :limit OFFSET :offset";
    List<FinanceWebhookDto> items = jdbcTemplate.query(sql, params, this::toWebhookDto);
    int totalPages = totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / pageSize);

    return PagedResponse.<FinanceWebhookDto>builder()
        .items(items)
        .page(pageNumber)
        .size(pageSize)
        .totalItems(totalItems)
        .totalPages(totalPages)
        .hasNext(pageNumber + 1 < totalPages)
        .hasPrevious(pageNumber > 0)
        .build();
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

  private FinancePayoutDto toPayoutDto(Payout p) {
    Room room = p.getRoom();
    User owner = p.getUser();
    PaymentIntent intent = p.getTriggeringPaymentIntent();
    PayoutMethod method = p.getPayoutMethod();

    return FinancePayoutDto.builder()
        .id(p.getId())
        .createdAt(p.getCreatedAt())
        .releaseAt(p.getReleaseAt())
        .processedAt(p.getProcessedAt())
        .nextRetryAt(p.getNextRetryAt())
        .status(p.getStatus())
        .amount(p.getAmount())
        .currency(p.getCurrency())
        .roomId(room != null ? room.getId() : null)
        .roomTitle(room != null ? room.getTitle() : null)
        .ownerUserId(owner != null ? owner.getId() : null)
        .ownerDisplayName(owner != null ? owner.getDisplayName() : null)
        .triggeringPaymentIntentId(intent != null ? intent.getId() : null)
        .payoutMethodId(method != null ? method.getId() : null)
        .payoutMethodPanMask(method != null ? method.getPanMask() : null)
        .providerName(method != null ? method.getProviderName() : null)
        .providerPayoutId(p.getProviderPayoutId())
        .failureReason(p.getFailureReason())
        .retryCount(p.getRetryCount())
        .build();
  }

  private FinanceWebhookDto toWebhookDto(ResultSet rs, int rowNum) throws SQLException {
    return FinanceWebhookDto.builder()
        .id(rs.getLong("id"))
        .receivedAt(toLocalDateTime(rs, "received_at"))
        .lastAttemptAt(toLocalDateTime(rs, "last_attempt_at"))
        .processedAt(toLocalDateTime(rs, "processed_at"))
        .nextRetryAt(toLocalDateTime(rs, "next_retry_at"))
        .deadLetteredAt(toLocalDateTime(rs, "dead_lettered_at"))
        .processingStatus(rs.getString("processing_status"))
        .callbackScript(rs.getString("callback_script"))
        .providerRequestId(rs.getString("provider_request_id"))
        .signatureValid(readNullableBoolean(rs, "signature_valid"))
        .attemptCount(readNullableInt(rs, "attempt_count"))
        .lastErrorCode(rs.getString("last_error_code"))
        .errorMessage(rs.getString("error_message"))
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

  private String normalizeStringFilter(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return raw.trim().toUpperCase();
  }

  private String normalizeRawFilter(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return raw.trim();
  }

  private Set<String> webhookInboxColumns() {
    return new HashSet<>(
        jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'freedom_webhook_inbox'",
            new MapSqlParameterSource(),
            String.class));
  }

  private String webhookSelect(Set<String> columns) {
    return String.join(
        ", ",
        "id",
        "provider_request_id",
        selectColumn(columns, "callback_script", "'result' AS callback_script"),
        selectColumn(columns, "signature_valid", "NULL AS signature_valid"),
        "received_at",
        selectColumn(columns, "processed_at", "NULL AS processed_at"),
        "processing_status",
        selectColumn(columns, "attempt_count", "0 AS attempt_count"),
        selectColumn(columns, "next_retry_at", "NULL AS next_retry_at"),
        selectColumn(columns, "last_error_code", "NULL AS last_error_code"),
        selectColumn(columns, "last_attempt_at", "NULL AS last_attempt_at"),
        selectColumn(columns, "dead_lettered_at", "NULL AS dead_lettered_at"),
        selectColumn(columns, "error_message", "NULL AS error_message"));
  }

  private String selectColumn(Set<String> columns, String column, String fallbackSql) {
    return columns.contains(column) ? column : fallbackSql;
  }

  private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private Boolean readNullableBoolean(ResultSet rs, String column) throws SQLException {
    boolean value = rs.getBoolean(column);
    return rs.wasNull() ? null : value;
  }

  private Integer readNullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
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
