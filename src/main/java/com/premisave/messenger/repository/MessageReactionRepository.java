package com.premisave.messenger.repository;

import com.premisave.messenger.entity.MessageReaction;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MessageReactionRepository extends MongoRepository<MessageReaction, String> {

    List<MessageReaction> findByMessageId(String messageId);
}