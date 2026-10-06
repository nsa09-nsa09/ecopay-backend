package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VisitRequest {
  @Size(max = 255, message = "Path is too long")
  private String path;
}
