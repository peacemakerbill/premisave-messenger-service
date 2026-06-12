package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Chat;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ChatRepository extends MongoRepository<Chat, String> {

    List<Chat> findByParticipantIdsContaining(String userId);

    @Query("{'participantIds': {$all: [?0, ?1]}, 'chatType': 'PRIVATE'}")
    Optional<Chat> findPrivateChatBetween(String userId1, String userId2);

    List<Chat> findByGroupId(String groupId);
}