package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Chat;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ChatRepository extends MongoRepository<Chat, String> {

    /**
     * Find all chats where the user is a participant
     */
    List<Chat> findByParticipantIdsContaining(String userId);

    /**
     * Find only active chats where the user is a participant (Recommended for getMyChats)
     */
    List<Chat> findByParticipantIdsContainingAndIsActiveTrue(String userId);

    /**
     * Find private chat between two specific users
     */
    @Query("{'participantIds': {$all: [?0, ?1]}, 'chatType': 'PRIVATE', 'isActive': true}")
    Optional<Chat> findPrivateChatBetween(String userId1, String userId2);

    /**
     * Find chats by group ID
     */
    List<Chat> findByGroupId(String groupId);

    /**
     * Optional: Find specific chat with active check
     */
    Optional<Chat> findByIdAndIsActiveTrue(String id);
}