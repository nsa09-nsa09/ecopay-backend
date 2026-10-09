package kz.hrms.splitupauth.service;

import java.util.Map;
import kz.hrms.splitupauth.entity.NotificationType;

/**
 * Localized, customer-safe copy for in-app and email notifications. Entries are TEMPLATES: the
 * contextual ones carry {@code {placeholders}} ({roomTitle}, {amount}, {currency}, {memberName})
 * that {@link #forRecipient} fills from the caller's structured params, so each recipient gets a
 * message about their specific room/amount instead of identical generic copy.
 */
final class NotificationMessages {

  record Copy(String title, String body) {}

  private NotificationMessages() {}

  static Copy forRecipient(
      NotificationType type, String recipientLocale, Map<String, String> params) {
    MailLocale locale = MailLocale.from(recipientLocale);
    Copy template = template(type, locale);
    return fill(template, params);
  }

  /**
   * Replaces {key} placeholders in the template with the caller's params (missing → left blank).
   */
  private static Copy fill(Copy template, Map<String, String> params) {
    if (params == null || params.isEmpty()) {
      return template;
    }
    return new Copy(fill(template.title(), params), fill(template.body(), params));
  }

  private static String fill(String s, Map<String, String> params) {
    if (s == null || s.indexOf('{') < 0) {
      return s;
    }
    String out = s;
    for (Map.Entry<String, String> e : params.entrySet()) {
      out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
    }
    return out;
  }

  private static Copy template(NotificationType type, MailLocale locale) {
    return switch (type) {
      case APPLICATION_SENT ->
          copy(
              locale,
              "Заявка отправлена",
              "Ваша заявка на участие в тарифе «{tariffName}» сервиса «{serviceName}» отправлена.",
              "Өтінім жіберілді",
              "«{serviceName}» сервисінің «{tariffName}» тарифіне қатысуға өтініміңіз жіберілді.",
              "Application sent",
              "Your application to join «{tariffName}» ({serviceName}) has been sent.");
      case MEMBER_JOINED ->
          copy(
              locale,
              "Новая заявка",
              "{memberName} подал(а) заявку в вашу комнату «{roomTitle}».",
              "Жаңа өтінім",
              "{memberName} «{roomTitle}» бөлмеңізге өтінім берді.",
              "New application",
              "{memberName} applied to join your room «{roomTitle}».");
      case PAYMENT_SUCCESS ->
          copy(
              locale,
              "Оплата подтверждена",
              "Оплата за участие в комнате «{roomTitle}» на сумму {amount} {currency} прошла успешно.",
              "Төлем расталды",
              "«{roomTitle}» бөлмесіне қатысу үшін {amount} {currency} төлемі сәтті өтті.",
              "Payment confirmed",
              "Your payment of {amount} {currency} for room «{roomTitle}» was successful.");
      case PAYMENT_FAILED ->
          copy(
              locale,
              "Оплата не прошла",
              "Не удалось выполнить оплату. Попробуйте ещё раз.",
              "Төлем өтпеді",
              "Төлемді орындау мүмкін болмады. Қайталап көріңіз.",
              "Payment failed",
              "We could not complete your payment. Please try again.");
      case RENEWAL_DUE ->
          copy(
              locale,
              "Пора продлить участие",
              "Участие в комнате «{roomTitle}» можно продлить. Оплатите {amount} {currency} до {dueDate}.",
              "Қатысуды ұзарту уақыты келді",
              "«{roomTitle}» бөлмесіндегі қатысуды ұзартуға болады. {dueDate} дейін {amount} {currency}"
                  + " төлеңіз.",
              "Time to renew",
              "You can renew your place in «{roomTitle}». Pay {amount} {currency} before {dueDate}.");
      case RENEWAL_OVERDUE ->
          copy(
              locale,
              "Срок продления истёк",
              "Оплата за следующий период в комнате «{roomTitle}» не поступила вовремя.",
              "Ұзарту мерзімі өтті",
              "«{roomTitle}» бөлмесіндегі келесі кезеңге төлем уақытында түспеді.",
              "Renewal overdue",
              "Payment for the next period in «{roomTitle}» was not received in time.");
      case ROOM_MEMBER_PAID ->
          copy(
              locale,
              "Участник оплатил",
              "Участник {memberName} оплатил место в комнате «{roomTitle}» и ожидает доступа.",
              "Қатысушы төледі",
              "{memberName} «{roomTitle}» бөлмесіндегі орнын төледі және қолжетімділікті күтуде.",
              "Member paid",
              "{memberName} paid for their place in room «{roomTitle}» and is waiting for access.");
      case OWNER_ACCESS_GRANTED ->
          copy(
              locale,
              "Доступ предоставлен",
              "Владелец предоставил доступ к «{tariffName}». Подтвердите его получение.",
              "Қолжетімділік берілді",
              "Иесі «{tariffName}» қолжетімділігін берді. Оны алғаныңызды растаңыз.",
              "Access granted",
              "The owner granted access to «{tariffName}». Please confirm that you received it.");
      case MEMBER_ACCESS_CONFIRMED ->
          copy(
              locale,
              "Доступ подтверждён",
              "Вы подтвердили получение доступа к тарифу «{tariffName}».",
              "Қолжетімділік расталды",
              "Сіз «{tariffName}» тарифіне қолжетімділікті алғаныңызды растадыңыз.",
              "Access confirmed",
              "You confirmed that you received access to «{tariffName}».");
      case MEMBER_CONFIRMED ->
          copy(
              locale,
              "Участник подтвердил доступ",
              "Участник {memberName} подтвердил доступ в комнате «{roomTitle}».",
              "Қатысушы қолжетімділікті растады",
              "{memberName} «{roomTitle}» бөлмесінде қолжетімділікті растады.",
              "Member confirmed access",
              "{memberName} confirmed access in room «{roomTitle}».");
      case MEMBERSHIP_ACTIVATED ->
          copy(
              locale,
              "Участие активно",
              "Ваше участие в комнате «{roomTitle}» активировано.",
              "Қатысу белсенді",
              "«{roomTitle}» бөлмесіндегі қатысуыңыз белсендірілді.",
              "Membership active",
              "Your membership in room «{roomTitle}» is now active.");
      case MEMBERSHIP_REJECTED ->
          copy(
              locale,
              "Заявка отклонена",
              "Ваша заявка на участие отклонена.",
              "Өтінім қабылданбады",
              "Қатысуға өтініміңіз қабылданбады.",
              "Application rejected",
              "Your membership application was rejected.");
      case CONFIRMATION_DEADLINE_WARNING ->
          copy(
              locale,
              "Подтвердите доступ",
              "Срок подтверждения доступа скоро истечёт.",
              "Қолжетімділікті растаңыз",
              "Қолжетімділікті растау мерзімі жақында аяқталады.",
              "Confirm access",
              "The access confirmation deadline is approaching.");
      case ROOM_ACTIVE ->
          copy(
              locale,
              "Комната активна",
              "Комната «{roomTitle}» перешла в активный статус.",
              "Бөлме белсенді",
              "«{roomTitle}» бөлмесі белсенді мәртебеге өтті.",
              "Room active",
              "Room «{roomTitle}» is now active.");
      case ROOM_FULL_AWAITING_ACCESS ->
          copy(
              locale,
              "Комната заполнена",
              "Все места в комнате «{roomTitle}» оплачены. Предоставьте участникам доступ.",
              "Бөлме толды",
              "«{roomTitle}» бөлмесіндегі барлық орын төленді. Қатысушыларға қолжетімділік беріңіз.",
              "Room is full",
              "All places in room «{roomTitle}» are paid. Please grant access to the members.");
      case CHAT_MESSAGE ->
          copy(
              locale,
              "Новое сообщение",
              "Новое сообщение в чате комнаты «{roomTitle}».",
              "Жаңа хабарлама",
              "«{roomTitle}» бөлмесінің чатында жаңа хабарлама.",
              "New message",
              "A new message in the chat of room «{roomTitle}».");
      case ROOM_COMPLETED ->
          copy(
              locale,
              "Комната завершена",
              "Работа комнаты завершена.",
              "Бөлме аяқталды",
              "Бөлменің жұмысы аяқталды.",
              "Room completed",
              "The room has been completed.");
      case ROOM_BLOCKED ->
          copy(
              locale,
              "Комната заблокирована",
              "Комната заблокирована. Подробности доступны в поддержке.",
              "Бөлме бұғатталды",
              "Бөлме бұғатталды. Толық ақпарат қолдау бөлімінде.",
              "Room blocked",
              "The room was blocked. Details are available from support.");
      case ROOM_CANCELLED ->
          copy(
              locale,
              "Комната отменена",
              "Комната была отменена.",
              "Бөлме тоқтатылды",
              "Бөлме тоқтатылды.",
              "Room cancelled",
              "The room was cancelled.");
      case REFUND_ISSUED ->
          copy(
              locale,
              "Возврат отправлен",
              "Возврат средств на сумму {amount} {currency} оформлен.",
              "Қаражатты қайтару жіберілді",
              "{amount} {currency} сомасындағы қаражатты қайтару рәсімделді.",
              "Refund issued",
              "A refund of {amount} {currency} has been issued.");
      case PAYOUT_SENT ->
          copy(
              locale,
              "Выплата отправлена",
              "Выплата на сумму {amount} {currency} отправлена на выбранный способ получения.",
              "Төлем жіберілді",
              "{amount} {currency} сомасындағы төлем таңдалған алу тәсіліне жіберілді.",
              "Payout sent",
              "A payout of {amount} {currency} was sent to your selected payout method.");
      case DISPUTE_OPENED ->
          copy(
              locale,
              "Спор открыт",
              "Ваш спор зарегистрирован и будет рассмотрен.",
              "Дау ашылды",
              "Дауыңыз тіркелді және қарастырылады.",
              "Dispute opened",
              "Your dispute was registered and will be reviewed.");
      case DISPUTE_RESOLVED ->
          copy(
              locale,
              "Решение по спору",
              "По вашему спору принято решение. Откройте спор для подробностей.",
              "Дау бойынша шешім",
              "Дауыңыз бойынша шешім қабылданды. Толық ақпарат үшін дауды ашыңыз.",
              "Dispute decision",
              "A decision was made on your dispute. Open it for details.");
      case TICKET_REPLY ->
          copy(
              locale,
              "Ответ поддержки",
              "Поддержка ответила на вашу заявку.",
              "Қолдау жауабы",
              "Қолдау қызметі өтініміңізге жауап берді.",
              "Support reply",
              "Support replied to your ticket.");
      case ACCOUNT_BANNED ->
          copy(
              locale,
              "Аккаунт заблокирован",
              "Ваш аккаунт заблокирован. Обратитесь в поддержку для подробностей.",
              "Аккаунт бұғатталды",
              "Аккаунтыңыз бұғатталды. Толық ақпарат үшін қолдауға хабарласыңыз.",
              "Account blocked",
              "Your account was blocked. Contact support for details.");
      case ACCOUNT_UNBANNED ->
          copy(
              locale,
              "Аккаунт разблокирован",
              "Ваш аккаунт снова активен.",
              "Аккаунт бұғаты алынды",
              "Аккаунтыңыз қайта белсенді.",
              "Account unblocked",
              "Your account is active again.");
    };
  }

  private static Copy copy(
      MailLocale locale,
      String ruTitle,
      String ruBody,
      String kkTitle,
      String kkBody,
      String enTitle,
      String enBody) {
    return new Copy(locale.pick(ruTitle, kkTitle, enTitle), locale.pick(ruBody, kkBody, enBody));
  }
}
