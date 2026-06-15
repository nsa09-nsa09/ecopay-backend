package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Row of the /admin/dashboard/popular-services chart — one entry per
 * subscription service, ranked by how many rooms (and active members) use it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PopularServiceDto {
    private Long serviceId;
    private String serviceName;
    private long roomsCount;
    private long activeMembersCount;
}
