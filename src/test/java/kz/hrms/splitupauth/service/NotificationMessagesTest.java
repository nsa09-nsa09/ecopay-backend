package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import kz.hrms.splitupauth.entity.NotificationType;
import org.junit.jupiter.api.Test;

/**
 * Block 9: notification copy is a localized TEMPLATE whose {placeholders} are filled from structured
 * params, so each recipient gets a message about their specific room/amount — not identical generic
 * copy. ru/kz/en all substitute; types without placeholders are unaffected; unknown params are safe.
 */
class NotificationMessagesTest {

  @Test
  void paymentSuccessFillsRoomAmountCurrency_perLocale() {
    Map<String, String> params =
        Map.of("roomTitle", "Netflix Family", "amount", "2322.50", "currency", "KZT");

    for (String locale : new String[] {"ru", "kz", "en"}) {
      String body =
          NotificationMessages.forRecipient(NotificationType.PAYMENT_SUCCESS, locale, params).body();
      assertTrue(body.contains("Netflix Family"), locale + " body should name the room: " + body);
      assertTrue(body.contains("2322.50"), locale + " body should include the amount: " + body);
      assertTrue(body.contains("KZT"), locale + " body should include the currency: " + body);
      assertFalse(body.contains("{"), locale + " body must have no leftover placeholders: " + body);
    }
  }

  @Test
  void differentRoomsProduceDifferentBodies_noMoreIdenticalNotifications() {
    String a =
        NotificationMessages.forRecipient(
                NotificationType.PAYMENT_SUCCESS,
                "ru",
                Map.of("roomTitle", "Room A", "amount", "1000", "currency", "KZT"))
            .body();
    String b =
        NotificationMessages.forRecipient(
                NotificationType.PAYMENT_SUCCESS,
                "ru",
                Map.of("roomTitle", "Room B", "amount", "2000", "currency", "KZT"))
            .body();
    assertNotEquals(a, b, "a member with two rooms must get two distinct messages");
  }

  @Test
  void typesWithoutPlaceholdersAreUnaffected_andEmptyParamsLeaveNoBraces() {
    String banned =
        NotificationMessages.forRecipient(NotificationType.ACCOUNT_BANNED, "ru", Map.of()).body();
    assertFalse(banned.isBlank());
    assertFalse(banned.contains("{"), "generic template must not contain placeholders");
  }
}
