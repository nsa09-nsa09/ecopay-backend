package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * B13: central Russian labels for room-event / moderation reason codes. The admin UI uses these to
 * replace raw code strings in the UI without having to maintain its own copy of the dictionary. New
 * codes added to the backend should also land here; an unknown code falls back to the raw value so
 * the panel never shows blank cells.
 */
public final class AdminLogLabels {

  private AdminLogLabels() {}

  /** Keyed on the exact {@code eventType} string stored in {@code room_event_log.event_type}. */
  private static final Map<String, String> EVENT_LABELS =
      Map.ofEntries(
          Map.entry("room_created", "Комната создана"),
          Map.entry("room_cancelled", "Комната отменена"),
          Map.entry("room_completed", "Комната завершена"),
          Map.entry("member_joined", "Подана заявка"),
          Map.entry("member_cancelled", "Участник отменил заявку"),
          Map.entry("membership_activated", "Участие активировано"),
          Map.entry("owner_access_granted", "Владелец выдал доступ"),
          Map.entry("member_confirmed", "Участник подтвердил доступ"),
          Map.entry("access_deemed_confirmed", "Доступ подтверждён автоматически"),
          Map.entry("payment_success", "Оплата прошла"),
          Map.entry("ADMIN_CONFIRMED_ACCESS", "Админ подтвердил доступ"),
          Map.entry("ADMIN_REJECTED_ACCESS", "Админ отклонил доступ"),
          Map.entry("ADMIN_BATCH_CONFIRMED_ACCESS", "Админ пакетно подтвердил доступ"),
          Map.entry("ROOM_BLOCKED", "Комната заблокирована"),
          Map.entry("ROOM_UNBLOCKED", "Комната разблокирована"),
          Map.entry("DISPUTE_DECIDED", "Спор решён"),
          Map.entry("OWNER_VIOLATION_SANCTIONED", "Применены санкции к владельцу"));

  /** Keyed on {@code moderation_queue.reason_code}. */
  private static final Map<String, String> REASON_LABELS =
      Map.of(
          "SUPPORT_ESCALATION", "Заявка в поддержку передана на проверку",
          "SUPPORT_TICKET", "По участию есть обращение в поддержку",
          "PENDING_TIMEOUT", "Владелец не выдал доступ вовремя");

  public static String eventLabel(String eventType) {
    if (eventType == null) return null;
    return EVENT_LABELS.getOrDefault(eventType, eventType);
  }

  public static String reasonDescription(String reasonCode) {
    if (reasonCode == null) return null;
    return REASON_LABELS.getOrDefault(reasonCode, reasonCode);
  }

  /**
   * Build a short "field: old → new" summary from the ObjectNode pair the event logger stores.
   * Status transitions are called out explicitly so the admin UI shows them inline instead of
   * forcing the viewer to compare two JSON blobs.
   */
  public static String summarize(JsonNode oldState, JsonNode newState) {
    if (newState == null || !newState.isObject()) return null;
    StringBuilder sb = new StringBuilder();
    newState
        .fieldNames()
        .forEachRemaining(
            field -> {
              JsonNode after = newState.get(field);
              JsonNode before =
                  oldState != null && oldState.isObject() ? oldState.get(field) : null;
              if (after == null || after.isNull()) return;
              if (before != null && before.equals(after)) return;
              if (sb.length() > 0) sb.append("; ");
              sb.append(field).append(": ");
              if (before != null && !before.isNull()) {
                sb.append(before.asText()).append(" → ");
              }
              sb.append(after.asText());
            });
    return sb.length() == 0 ? null : sb.toString();
  }
}
