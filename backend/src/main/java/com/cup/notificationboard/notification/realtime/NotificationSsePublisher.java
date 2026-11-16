package com.cup.notificationboard.notification.realtime;

import com.cup.notificationboard.notification.service.NotificationChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class NotificationSsePublisher {

    private final NotificationSseHub sseHub;

    public NotificationSsePublisher(NotificationSseHub sseHub) {
        this.sseHub = sseHub;
    }

    // Emit only after the database transaction commits, so clients never see a rolled-back change.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationChanged(NotificationChanged change) {
        sseHub.publish(change);
    }
}
