package com.cup.notificationboard.notification.persistence;

import com.cup.notificationboard.notification.model.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID>,
        JpaSpecificationExecutor<NotificationEntity> {

    Optional<NotificationEntity> findBySourceMessageId(String sourceMessageId);
}
