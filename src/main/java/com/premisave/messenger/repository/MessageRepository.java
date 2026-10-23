package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Message;
import com.premisave.messenger.enums.MessageDeliveryState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends MongoRepository<Message, String> {

    List<Message> findByChatIdOrderByCreatedAtDesc(String chatId, Pageable pageable);

    @Query("{'chatId': ?0, 'createdAt': {$gte: ?1}}")
    List<Message> findRecentMessages(String chatId, LocalDateTime since);

    long countByChatIdAndReadByNotContaining(String chatId, String userId);

    // ===== IDEMPOTENCY SUPPORT =====
    /**
     * Find message by idempotency key to prevent duplicate sends
     */
    Optional<Message> findByIdempotencyKey(String idempotencyKey);

    // ===== DELIVERY STATE QUERIES =====
    /**
     * Find messages that need retry (pending, failed, or partially notified)
     */
    @Query("{'deliveryState': {$in: ['PENDING', 'FAILED_TO_NOTIFY', 'PARTIALLY_NOTIFIED']}, 'retryCount': {$lt: 3}}")
    List<Message> findMessagesToRetry();

    /**
     * Find all failed messages
     */
    @Query("{'deliveryState': 'FAILED'}")
    List<Message> findFailedMessages();

    /**
     * Count messages in delivery state for a chat
     */
    long countByChatIdAndDeliveryState(String chatId, MessageDeliveryState state);

    /** Messages from other people that this user has not read. Own, deleted and hidden messages are not counted. */
    @Query(value = "{'chatId': ?0, 'senderId': {$ne: ?1}, 'readBy': {$ne: ?1}, 'isActive': true, 'deletedForUsers': {$ne: ?1}}", count = true)
    long countUnreadFor(String chatId, String userId);

    @Query("{'chatId': ?0, 'senderId': {$ne: ?1}, 'readBy': {$ne: ?1}, 'isActive': true, 'deletedForUsers': {$ne: ?1}}")
    List<Message> findUnreadFor(String chatId, String userId, org.springframework.data.domain.Pageable pageable);
}