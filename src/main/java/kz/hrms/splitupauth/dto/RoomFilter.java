package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import lombok.Builder;
import lombok.Data;

/** Optional filters for the room catalog listing. Any null field is ignored. */
@Data
@Builder
public class RoomFilter {
  private RoomStatus status;
  private RoomType roomType;
  private Long categoryId;
  private Long serviceId;
  private BigDecimal priceMin;
  private BigDecimal priceMax;
  private Integer minFreeSeats;
  private AccessType accessType;
  private String region;
  private Boolean verifiedOwnerOnly;

  /**
   * B9: when true, the list returns only rooms a new user can actually join right now — OPEN or
   * ACTIVE status (both allow joining), at least one free marketplace seat, start_date still in the
   * future. Ignored when null/false.
   */
  private Boolean joinable;
}
