package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

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
}
