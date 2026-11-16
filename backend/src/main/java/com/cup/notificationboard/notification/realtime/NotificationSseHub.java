package com.cup.notificationboard.notification.realtime;

import com.cup.notificationboard.notification.service.NotificationChanged;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class NotificationSseHub {

    private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(error -> clients.remove(emitter));
        return emitter;
    }

    public void publish(NotificationChanged change) {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(change.eventType())
                .data(change.notification());

        for (SseEmitter client : clients) {
            try {
                client.send(event);
            } catch (IOException | IllegalStateException exception) {
                clients.remove(client);
                client.completeWithError(exception);
            }
        }
    }
}
