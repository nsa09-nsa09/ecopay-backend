package kz.hrms.splitupauth.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.dto.NotificationPreferenceDto;
import kz.hrms.splitupauth.dto.UpdateNotificationPreferenceRequest;
import kz.hrms.splitupauth.entity.NotificationPreference;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns per-(user, type) notification channel preferences.
 *
 * <p>Storage is sparse: a row exists only once a user diverges from the all-on default, so {@link
 * #channelsFor} treats a missing row as "both channels on". The {@code email} flag is always gated
 * by {@link NotificationType#isEmailEligible()} — a user can't opt into email for an in-app-only
 * type.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

  private final NotificationPreferenceRepository preferenceRepository;

  /** Effective channel decision for one type, used by NotificationService. */
  @Transactional(readOnly = true)
  public Channels channelsFor(User user, NotificationType type) {
    return preferenceRepository
        .findByUserAndType(user, type)
        .map(p -> new Channels(p.isInApp(), p.isEmail() && type.isEmailEligible()))
        .orElseGet(() -> new Channels(true, type.isEmailEligible()));
  }

  /** Full preference matrix for the settings screen — every type, defaulted. */
  @Transactional(readOnly = true)
  public List<NotificationPreferenceDto> list(User user) {
    Map<NotificationType, NotificationPreference> stored = new EnumMap<>(NotificationType.class);
    preferenceRepository.findByUser(user).forEach(p -> stored.put(p.getType(), p));

    List<NotificationPreferenceDto> result = new ArrayList<>();
    for (NotificationType type : NotificationType.values()) {
      result.add(NotificationPreferenceDto.resolve(type, stored.get(type)));
    }
    return result;
  }

  /** Upsert each submitted row, then return the refreshed full matrix. */
  @Transactional
  public List<NotificationPreferenceDto> update(
      User user, UpdateNotificationPreferenceRequest request) {
    for (UpdateNotificationPreferenceRequest.Item item : request.getPreferences()) {
      NotificationType type = item.getType();
      NotificationPreference pref =
          preferenceRepository
              .findByUserAndType(user, type)
              .orElseGet(() -> NotificationPreference.builder().user(user).type(type).build());
      pref.setInApp(item.isInApp());
      // Persist the requested email value even for ineligible types; it's
      // harmless (delivery still gates on emailEligible) and keeps the row
      // honest if the type later becomes eligible.
      pref.setEmail(item.isEmail());
      preferenceRepository.save(pref);
    }
    return list(user);
  }

  /** Resolved channel decision: whether to create an in-app row and/or email. */
  public record Channels(boolean inApp, boolean email) {}
}
