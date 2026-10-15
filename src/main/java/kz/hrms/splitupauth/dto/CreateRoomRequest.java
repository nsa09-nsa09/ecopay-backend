package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.RoomType;
import lombok.Data;

@Data
public class CreateRoomRequest {

  private Long categoryId;

  @NotNull(message = "Выберите сервис")
  private Long serviceId;

  // Pricing-critical fields (price, currency, billing period, seat count) are
  // owned by the admin-managed tariff plan — not the room owner. Selecting a
  // tariff is therefore mandatory, and the room snapshots those values from it
  // at creation time. The owner cannot override them here.
  @NotNull(message = "Выберите тариф")
  private Long tariffPlanId;

  private RoomType roomType;

  @NotBlank(message = "Укажите название комнаты")
  @Size(min = 3, max = 150, message = "Название должно быть от 3 до 150 символов")
  private String title;

  /** B16: description has a DB column cap. Keep server validation in sync with it. */
  @Size(max = 2000, message = "Описание должно быть не длиннее 2000 символов")
  private String description;

  @Min(1)
  @Max(2)
  private Integer existingMembersCount;

  private LocalDateTime startDate;

  private String cancellationPolicy;

  private String providerName;

  private String tariffNameSnapshot;

  private ConnectionType connectionType;

  private String operatorRestrictions;

  private Boolean operatorTermsConfirmed;

  // Access type — may be omitted; inherited from the tariff's defaults when null (hybrid).
  private AccessType accessType;

  @Size(max = 10, message = "Код региона должен быть не длиннее 10 символов")
  private String regionRestriction;

  private Boolean requiresEmailForInvite;

  private Boolean emailChangeForbidden;

  @Min(value = 0, message = "Срок выдачи доступа не может быть отрицательным")
  private Integer accessGrantSlaHours;
}
