package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.ConnectionType;
import lombok.Data;

@Data
public class UpdateRoomRequest {

  @Size(min = 3, max = 150, message = "Название должно быть от 3 до 150 символов")
  private String title;

  @Size(max = 2000, message = "Описание должно быть не длиннее 2000 символов")
  private String description;

  // Price, currency, billing period, and seat count are tariff-controlled
  // (set by the admin on the tariff plan) and intentionally NOT editable here.

  @Size(max = 1000, message = "Политика отмены должна быть не длиннее 1000 символов")
  private String cancellationPolicy;

  @Size(max = 120, message = "Название провайдера должно быть не длиннее 120 символов")
  private String providerName;

  @Size(max = 150, message = "Название тарифа должно быть не длиннее 150 символов")
  private String tariffNameSnapshot;

  private ConnectionType connectionType;

  @Size(max = 1000, message = "Ограничения оператора должны быть не длиннее 1000 символов")
  private String operatorRestrictions;

  private Boolean operatorTermsConfirmed;

  private AccessType accessType;

  @Size(max = 10, message = "Код региона должен быть не длиннее 10 символов")
  private String regionRestriction;

  private Boolean requiresEmailForInvite;

  private Boolean emailChangeForbidden;

  @Min(value = 0, message = "Срок выдачи доступа не может быть отрицательным")
  private Integer accessGrantSlaHours;
}
