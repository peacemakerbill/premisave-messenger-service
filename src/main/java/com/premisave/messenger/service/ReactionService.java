package com.premisave.messenger.service;

import com.premisave.messenger.entity.MessageReaction;
import com.premisave.messenger.enums.ReactionType;
import com.premisave.messenger.repository.MessageReactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReactionService {

    private final MessageReactionRepository reactionRepository;

    public void addReaction(String messageId, String userId, ReactionType reactionType) {
        MessageReaction reaction = new MessageReaction();
        reaction.setMessageId(messageId);
        reaction.setUserId(userId);
        reaction.setReactionType(reactionType);
        reactionRepository.save(reaction);
    }
}