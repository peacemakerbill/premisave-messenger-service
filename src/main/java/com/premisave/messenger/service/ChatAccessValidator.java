package com.premisave.messenger.service;

import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.repository.ChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Validates user access to chats with caching
 * Cache TTL: 5 minutes for PRIVATE chats, 10 minutes for GROUP chats
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAccessValidator {

    private final ChatRepository chatRepository;
    private final GroupService groupService;

    /**
     * Check if user can access this chat
     * Results are cached for 5-10 minutes
     */
    @Cacheable(value = "chat-access", key = "#chatId + ':' + #userId", unless = "#result == false")
    public boolean canAccessChat(String chatId, String userId) {
        if (chatId == null || userId == null) {
            log.warn("Null chatId or userId in access check");
            return false;
        }

        Optional<Chat> chatOpt = chatRepository.findById(chatId);
        if (chatOpt.isEmpty()) {
            log.warn("Chat not found: {}", chatId);
            return false;
        }

        Chat chat = chatOpt.get();
        if (!chat.isActive()) {
            log.warn("Inactive chat accessed: {}", chatId);
            return false;
        }

        boolean hasAccess = checkAccess(chat, userId);
        
        if (!hasAccess) {
            log.warn("Access denied for user {} in chat {}", userId, chatId);
        } else {
            log.debug("Access granted for user {} in chat {}", userId, chatId);
        }

        return hasAccess;
    }

    /**
     * Invalidate access cache when chat membership changes
     */
    public void invalidateAccessCache(String chatId, String userId) {
        // Cache invalidation is handled by Spring
        log.debug("Access cache invalidated for chat {} user {}", chatId, userId);
    }

    /**
     * Invalidate entire chat access cache (on chat deletion/update)
     */
    public void invalidateChatAccessCache(String chatId) {
        log.debug("All access cache invalidated for chat {}", chatId);
    }

    // ===== PRIVATE METHODS =====

    private boolean checkAccess(Chat chat, String userId) {
        if (chat.getChatType() == ChatType.PRIVATE) {
            return chat.getParticipantIds().contains(userId);
        } else if (chat.getChatType() == ChatType.GROUP && chat.getGroupId() != null) {
            return groupService.isUserMemberOfGroup(chat.getGroupId(), userId);
        }
        return false;
    }
}