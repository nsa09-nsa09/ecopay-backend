package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.NotificationDto;

/**
 * Internal application event published by {@link NotificationService} once a notification's in-app
 * row is persisted. Consumed after transaction commit so we never push/email for a change that gets
 * rolled back. See {@code NotificationDeliveryListener}.
 *
 * @param userId recipient id (for the STOMP topic)
 * @param email recipient email, or null if not emailing
 * @param dto payload to push over websocket, or null if in-app is off
 * @param sendEmail whether to also send an email for this event
 * @param locale language to render the email chrome in (buttons, footer)
 */
public record NotificationDeliveryEvent(
    Long userId,
    String email,
    NotificationDto dto,
    boolean sendEmail,
    String emailSubject,
    String emailBody,
    String link,
    MailLocale locale) {}
