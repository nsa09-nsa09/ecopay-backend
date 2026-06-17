package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.websocket.NotificationRealtimeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Performs the out-of-band channels (websocket push + email) for a notification
 * AFTER the triggering transaction commits, on an async thread.
 *
 * <p>Separate bean from {@link NotificationService} so the {@code @Async} +
 * {@code @TransactionalEventListener} proxying is unambiguous. Each channel is
 * isolated in its own try/catch: a failed email never blocks the push, and
 * neither can surface back into the (already-committed) business transaction.
 *
 * <p>{@code fallbackExecution = true} so notifications emitted outside any
 * transaction (e.g. from a scheduler or test) are still delivered.
 */
@Component
@RequiredArgsConstructor
public class NotificationDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryListener.class);

    private final NotificationRealtimeService realtimeService;
    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDelivery(NotificationDeliveryEvent event) {
        if (event.dto() != null) {
            try {
                realtimeService.push(event.userId(), event.dto());
            } catch (Exception e) {
                log.warn("Failed to push notification to user={}: {}", event.userId(), e.getMessage());
            }
        }

        if (event.sendEmail() && event.email() != null && !event.email().isBlank()) {
            try {
                emailService.sendNotificationEmail(
                        event.email(), event.emailSubject(), event.emailBody(), event.link());
            } catch (Exception e) {
                log.warn("Failed to email notification to user={}: {}", event.userId(), e.getMessage());
            }
        }
    }
}
