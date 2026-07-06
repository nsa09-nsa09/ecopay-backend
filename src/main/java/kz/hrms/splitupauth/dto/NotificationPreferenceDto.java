package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.NotificationCategory;
import kz.hrms.splitupauth.entity.NotificationPreference;
import kz.hrms.splitupauth.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per {@link NotificationType} in the preferences screen. {@code email} is only meaningful
 * when {@code emailEligible} is true; the UI hides/disables the email toggle for in-app-only types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferenceDto {
  private NotificationType type;
  private NotificationCategory category;
  private boolean inApp;
  private boolean email;
  private boolean emailEligible;

  /** Effective preference for a type, using the stored row or the all-on default. */
  public static NotificationPreferenceDto resolve(
      NotificationType type, NotificationPreference pref) {
    boolean inApp = pref == null || pref.isInApp();
    boolean email = pref == null || pref.isEmail();
    return NotificationPreferenceDto.builder()
        .type(type)
        .category(type.getCategory())
        .inApp(inApp)
        .email(email && type.isEmailEligible())
        .emailEligible(type.isEmailEligible())
        .build();
  }
}
