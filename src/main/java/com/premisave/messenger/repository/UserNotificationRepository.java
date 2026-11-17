package com.premisave.messenger.repository;

import com.premisave.messenger.entity.UserNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface UserNotificationRepository extends MongoRepository<UserNotification, String> {

    List<UserNotification> findByUserId(String userId, Pageable pageable);

    long countByUserIdAndReadFalse(String userId);

    long deleteByUserId(String userId);
}
