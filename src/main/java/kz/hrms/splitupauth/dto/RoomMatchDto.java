package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of the FIFO catalog match. {@code action="JOIN"} means the frontend should route the user
 * into an existing room ({@code roomId}); {@code "CREATE"} means no eligible room was found and the
 * user has to start a new one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMatchDto {
  private String action;
  private Long roomId;
}
