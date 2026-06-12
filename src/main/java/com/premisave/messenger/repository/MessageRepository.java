package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends MongoRepository<Message, String> {

    List<Message> findByChatIdOrderByCreatedAtDesc(String chatId, Pageable pageable);

    @Query("{'chatId': ?0, 'createdAt': {$gte: ?1}}")
    List<Message> findRecentMessages(String chatId, LocalDateTime since);

    long countByChatIdAndReadByNotContaining(String chatId, String userId);
}