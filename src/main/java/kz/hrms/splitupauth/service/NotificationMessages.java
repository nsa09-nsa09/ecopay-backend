package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.NotificationType;

/** Localized, customer-safe copy for in-app and email notifications. */
final class NotificationMessages {

  record Copy(String title, String body) {}

  private NotificationMessages() {}

  static Copy forRecipient(NotificationType type, String recipientLocale) {
    MailLocale locale = MailLocale.from(recipientLocale);
    return switch (type) {
      case APPLICATION_SENT -> copy(locale,
          "Заявка отправлена", "Ваша заявка на участие отправлена.",
          "Өтінім жіберілді", "Қатысуға өтініміңіз жіберілді.",
          "Application sent", "Your membership application has been sent.");
      case MEMBER_JOINED -> copy(locale,
          "Новая заявка", "В вашу комнату поступила новая заявка.",
          "Жаңа өтінім", "Бөлмеңізге жаңа өтінім түсті.",
          "New application", "Your room has received a new application.");
      case PAYMENT_SUCCESS -> copy(locale,
          "Оплата подтверждена", "Оплата прошла успешно.",
          "Төлем расталды", "Төлем сәтті өтті.",
          "Payment confirmed", "Your payment was successful.");
      case PAYMENT_FAILED -> copy(locale,
          "Оплата не прошла", "Не удалось выполнить оплату. Попробуйте ещё раз.",
          "Төлем өтпеді", "Төлемді орындау мүмкін болмады. Қайталап көріңіз.",
          "Payment failed", "We could not complete your payment. Please try again.");
      case ROOM_MEMBER_PAID -> copy(locale,
          "Участник оплатил", "Участник оплатил место и ожидает предоставления доступа.",
          "Қатысушы төледі", "Қатысушы орнын төлеп, қолжетімділікті күтуде.",
          "Member paid", "A member paid for their place and is waiting for access.");
      case OWNER_ACCESS_GRANTED -> copy(locale,
          "Доступ предоставлен", "Владелец предоставил доступ. Подтвердите его получение.",
          "Қолжетімділік берілді", "Иесі қолжетімділік берді. Оны алғаныңызды растаңыз.",
          "Access granted", "The owner granted access. Please confirm that you received it.");
      case MEMBER_ACCESS_CONFIRMED -> copy(locale,
          "Доступ подтверждён", "Вы подтвердили получение доступа.",
          "Қолжетімділік расталды", "Сіз қолжетімділікті алғаныңызды растадыңыз.",
          "Access confirmed", "You confirmed that you received access.");
      case MEMBER_CONFIRMED -> copy(locale,
          "Участник подтвердил доступ", "Участник подтвердил получение доступа.",
          "Қатысушы қолжетімділікті растады", "Қатысушы қолжетімділікті алғанын растады.",
          "Member confirmed access", "A member confirmed that they received access.");
      case MEMBERSHIP_ACTIVATED -> copy(locale,
          "Участие активно", "Ваше участие активировано.",
          "Қатысу белсенді", "Сіздің қатысуыңыз белсендірілді.",
          "Membership active", "Your membership is now active.");
      case MEMBERSHIP_REJECTED -> copy(locale,
          "Заявка отклонена", "Ваша заявка на участие отклонена.",
          "Өтінім қабылданбады", "Қатысуға өтініміңіз қабылданбады.",
          "Application rejected", "Your membership application was rejected.");
      case CONFIRMATION_DEADLINE_WARNING -> copy(locale,
          "Подтвердите доступ", "Срок подтверждения доступа скоро истечёт.",
          "Қолжетімділікті растаңыз", "Қолжетімділікті растау мерзімі жақында аяқталады.",
          "Confirm access", "The access confirmation deadline is approaching.");
      case ROOM_ACTIVE -> copy(locale,
          "Комната активна", "Ваша комната перешла в активный статус.",
          "Бөлме белсенді", "Бөлмеңіз белсенді мәртебеге өтті.",
          "Room active", "Your room is now active.");
      case ROOM_FULL_AWAITING_ACCESS -> copy(locale,
          "Комната заполнена", "Все места оплачены. Предоставьте участникам доступ.",
          "Бөлме толды", "Барлық орын төленді. Қатысушыларға қолжетімділік беріңіз.",
          "Room is full", "All places are paid. Please grant access to the members.");
      case CHAT_MESSAGE -> copy(locale,
          "Новое сообщение", "В чате комнаты появилось новое сообщение.",
          "Жаңа хабарлама", "Бөлме чатында жаңа хабарлама бар.",
          "New message", "There is a new message in the room chat.");
      case ROOM_COMPLETED -> copy(locale,
          "Комната завершена", "Работа комнаты завершена.",
          "Бөлме аяқталды", "Бөлменің жұмысы аяқталды.",
          "Room completed", "The room has been completed.");
      case ROOM_BLOCKED -> copy(locale,
          "Комната заблокирована", "Комната заблокирована. Подробности доступны в поддержке.",
          "Бөлме бұғатталды", "Бөлме бұғатталды. Толық ақпарат қолдау бөлімінде.",
          "Room blocked", "The room was blocked. Details are available from support.");
      case ROOM_CANCELLED -> copy(locale,
          "Комната отменена", "Комната была отменена.",
          "Бөлме тоқтатылды", "Бөлме тоқтатылды.",
          "Room cancelled", "The room was cancelled.");
      case REFUND_ISSUED -> copy(locale,
          "Возврат отправлен", "Возврат средств оформлен.",
          "Қаражатты қайтару жіберілді", "Қаражатты қайтару рәсімделді.",
          "Refund issued", "Your refund has been issued.");
      case PAYOUT_SENT -> copy(locale,
          "Выплата отправлена", "Выплата отправлена на выбранный способ получения.",
          "Төлем жіберілді", "Төлем таңдалған алу тәсіліне жіберілді.",
          "Payout sent", "The payout was sent to your selected payout method.");
      case DISPUTE_OPENED -> copy(locale,
          "Спор открыт", "Ваш спор зарегистрирован и будет рассмотрен.",
          "Дау ашылды", "Дауыңыз тіркелді және қарастырылады.",
          "Dispute opened", "Your dispute was registered and will be reviewed.");
      case DISPUTE_RESOLVED -> copy(locale,
          "Решение по спору", "По вашему спору принято решение. Откройте спор для подробностей.",
          "Дау бойынша шешім", "Дауыңыз бойынша шешім қабылданды. Толық ақпарат үшін дауды ашыңыз.",
          "Dispute decision", "A decision was made on your dispute. Open it for details.");
      case TICKET_REPLY -> copy(locale,
          "Ответ поддержки", "Поддержка ответила на вашу заявку.",
          "Қолдау жауабы", "Қолдау қызметі өтініміңізге жауап берді.",
          "Support reply", "Support replied to your ticket.");
      case ACCOUNT_BANNED -> copy(locale,
          "Аккаунт заблокирован", "Ваш аккаунт заблокирован. Обратитесь в поддержку для подробностей.",
          "Аккаунт бұғатталды", "Аккаунтыңыз бұғатталды. Толық ақпарат үшін қолдауға хабарласыңыз.",
          "Account blocked", "Your account was blocked. Contact support for details.");
      case ACCOUNT_UNBANNED -> copy(locale,
          "Аккаунт разблокирован", "Ваш аккаунт снова активен.",
          "Аккаунт бұғаты алынды", "Аккаунтыңыз қайта белсенді.",
          "Account unblocked", "Your account is active again.");
    };
  }

  private static Copy copy(
      MailLocale locale,
      String ruTitle, String ruBody,
      String kkTitle, String kkBody,
      String enTitle, String enBody) {
    return new Copy(
        locale.pick(ruTitle, kkTitle, enTitle),
        locale.pick(ruBody, kkBody, enBody));
  }
}
