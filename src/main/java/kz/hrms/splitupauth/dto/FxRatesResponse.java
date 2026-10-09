package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FxRatesResponse {
  /** Always "KZT" — every rate is expressed as "1 currency unit = X KZT". */
  private String base;

  /** Last time the snapshot was refreshed (Asia/Almaty wall clock). */
  private LocalDateTime updatedAt;

  /** ISO code → rate against KZT. */
  private Map<String, BigDecimal> rates;
}
