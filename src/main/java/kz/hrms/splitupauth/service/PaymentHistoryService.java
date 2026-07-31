package kz.hrms.splitupauth.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.dto.PaymentHistoryItemDto;
import kz.hrms.splitupauth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentHistoryService {

  private final NamedParameterJdbcTemplate jdbc;

  public PageResponse<PaymentHistoryItemDto> history(
      User user,
      int page,
      int size,
      String kind,
      String status,
      LocalDateTime dateFrom,
      LocalDateTime dateTo) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));

    Map<String, Object> params = new HashMap<>();
    params.put("userId", user.getId());
    params.put("limit", safeSize);
    params.put("offset", safePage * safeSize);

    String union = baseUnionSql();
    StringBuilder where = new StringBuilder(" where 1=1 ");
    if (kind != null && !kind.isBlank()) {
      where.append(" and h.kind = :kind ");
      params.put("kind", kind.trim().toUpperCase());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and h.status = :status ");
      params.put("status", status.trim().toUpperCase());
    }
    if (dateFrom != null) {
      where.append(" and h.created_at >= :dateFrom ");
      params.put("dateFrom", dateFrom);
    }
    if (dateTo != null) {
      where.append(" and h.created_at <= :dateTo ");
      params.put("dateTo", dateTo);
    }

    String from = " from (" + union + ") h " + where;
    long total = jdbc.queryForObject("select count(*)" + from, params, Long.class);
    var items =
        jdbc.query(
            "select *"
                + from
                + " order by h.created_at desc, h.id desc limit :limit offset :offset",
            params,
            (rs, rowNum) -> map(rs));

    return PageResponse.<PaymentHistoryItemDto>builder()
        .page(safePage)
        .size(safeSize)
        .totalItems(total)
        .totalPages((int) Math.ceil(total / (double) safeSize))
        .items(items)
        .build();
  }

  private String baseUnionSql() {
    return """
        select
          pi.id as id,
          'PAYMENT' as kind,
          'DEBIT' as direction,
          pi.status as status,
          pi.amount as amount,
          coalesce(pi.currency, 'KZT') as currency,
          pi.created_at as created_at,
          pi.updated_at as updated_at,
          rm.room_id as room_id,
          room.title as room_title,
          pi.id as payment_intent_id,
          tx.id as payment_transaction_id,
          cast(null as bigint) as refund_id,
          cast(null as bigint) as payout_id,
          pi.provider_name as provider_name,
          tx.card_pan_mask as card_pan_mask,
          pi.failure_code as failure_code,
          cast(null as timestamp) as release_at
        from payment_intents pi
        join room_members rm on rm.id = pi.room_member_id
        join rooms room on room.id = rm.room_id
        left join payment_transactions tx
          on tx.payment_intent_id = pi.id and tx.type = 'CHARGE' and tx.status = 'SUCCESS'
        where pi.user_id = :userId

        union all

        select
          r.id as id,
          'REFUND' as kind,
          'CREDIT' as direction,
          r.status as status,
          r.amount as amount,
          r.currency as currency,
          r.created_at as created_at,
          r.updated_at as updated_at,
          coalesce(tx.room_id, rm.room_id) as room_id,
          room.title as room_title,
          pi.id as payment_intent_id,
          tx.id as payment_transaction_id,
          r.id as refund_id,
          cast(null as bigint) as payout_id,
          tx.provider_name as provider_name,
          tx.card_pan_mask as card_pan_mask,
          cast(null as varchar) as failure_code,
          cast(null as timestamp) as release_at
        from refund_transactions r
        join payment_transactions tx on tx.id = r.payment_transaction_id
        join payment_intents pi on pi.id = tx.payment_intent_id
        join room_members rm on rm.id = pi.room_member_id
        join rooms room on room.id = coalesce(tx.room_id, rm.room_id)
        where pi.user_id = :userId

        union all

        select
          p.id as id,
          'PAYOUT' as kind,
          'CREDIT' as direction,
          p.status as status,
          p.amount as amount,
          p.currency as currency,
          p.created_at as created_at,
          p.updated_at as updated_at,
          p.room_id as room_id,
          room.title as room_title,
          p.triggering_payment_intent_id as payment_intent_id,
          cast(null as bigint) as payment_transaction_id,
          cast(null as bigint) as refund_id,
          p.id as payout_id,
          pm.provider_name as provider_name,
          pm.pan_mask as card_pan_mask,
          cast(null as varchar) as failure_code,
          p.release_at as release_at
        from payouts p
        left join rooms room on room.id = p.room_id
        left join payout_methods pm on pm.id = p.payout_method_id
        where p.user_id = :userId
        """;
  }

  private PaymentHistoryItemDto map(ResultSet rs) throws SQLException {
    return PaymentHistoryItemDto.builder()
        .id(rs.getLong("id"))
        .kind(rs.getString("kind"))
        .direction(rs.getString("direction"))
        .status(rs.getString("status"))
        .amount(rs.getBigDecimal("amount"))
        .currency(rs.getString("currency"))
        .createdAt(rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime())
        .updatedAt(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime())
        .roomId(getLongOrNull(rs, "room_id"))
        .roomTitle(rs.getString("room_title"))
        .paymentIntentId(getLongOrNull(rs, "payment_intent_id"))
        .paymentTransactionId(getLongOrNull(rs, "payment_transaction_id"))
        .refundId(getLongOrNull(rs, "refund_id"))
        .payoutId(getLongOrNull(rs, "payout_id"))
        .providerName(rs.getString("provider_name"))
        .cardPanMask(rs.getString("card_pan_mask"))
        .failureCode(rs.getString("failure_code"))
        .releaseAt(rs.getTimestamp("release_at") == null ? null : rs.getTimestamp("release_at").toLocalDateTime())
        .build();
  }

  private Long getLongOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
