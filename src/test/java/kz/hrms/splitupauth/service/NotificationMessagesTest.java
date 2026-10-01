package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import kz.hrms.splitupauth.entity.NotificationType;
import org.junit.jupiter.api.Test;

class NotificationMessagesTest {

  @Test
  void refundCopyFollowsRecipientLocale() {
    assertEquals("Возврат отправлен", copy(NotificationType.REFUND_ISSUED, "ru").title());
    assertEquals("Қаражатты қайтару жіберілді", copy(NotificationType.REFUND_ISSUED, "kz").title());
    assertEquals("Refund issued", copy(NotificationType.REFUND_ISSUED, "en-US").title());
  }

  @Test
  void roomAndSupportCopyDoesNotExposeTechnicalCodes() {
    for (String locale : new String[] {"ru", "kk", "en"}) {
      NotificationMessages.Copy room = copy(NotificationType.ROOM_BLOCKED, locale);
      NotificationMessages.Copy support = copy(NotificationType.TICKET_REPLY, locale);
      assertFalse(room.title().contains("ROOM_BLOCKED"));
      assertFalse(support.title().contains("TICKET_REPLY"));
    }
  }

  @Test
  void unknownLocaleUsesRussianProductDefault() {
    assertEquals("Оплата подтверждена", copy(NotificationType.PAYMENT_SUCCESS, "unknown").title());
  }

  private NotificationMessages.Copy copy(NotificationType type, String locale) {
    return NotificationMessages.forRecipient(type, locale);
  }
}
