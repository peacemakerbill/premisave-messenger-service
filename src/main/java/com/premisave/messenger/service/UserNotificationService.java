package com.premisave.messenger.service;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.response.NotificationResponse;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.UserNotification;
import com.premisave.messenger.enums.NotificationType;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.GroupRepository;
import com.premisave.messenger.repository.UserNotificationRepository;
import com.premisave.messenger.util.UserNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notifications for the person something happened to: made an admin or the owner, demoted, added to a group,
 * removed from a group.
 *
 * Each one is stored, so it is waiting in the person's list even if they were offline, pushed to the person's
 * open app straight away, and emailed. Creating one is best effort: it never fails the group action that
 * caused it. Nothing is sent to the person who did the action themselves.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private static final int MAX_LIST = 100;

    private final UserNotificationRepository repository;
    private final MongoTemplate mongoTemplate;
    private final RedisMessagePublisher redisMessagePublisher;
    private final NotificationService notificationService;
    private final AuthServiceClient authServiceClient;
    private final GroupRepository groupRepository;

    /** At most one mention email per person and chat in this many minutes. The in-app notification is never held back. */
    @Value("${notifications.mention-email-cooldown-minutes:5}")
    private int mentionEmailCooldownMinutes;

    /** An @all in a group bigger than this still notifies everyone in the app, but sends no emails. */
    @Value("${notifications.mention-all-email-max-members:100}")
    private int mentionAllEmailMaxMembers;

    // ----------------------------------------------------------------- create

    /** Notifies every person in userIds. The actor's name is looked up once. */
    public void notifyEach(Collection<String> userIds, NotificationType type, String groupId, String chatId,
                           String groupName, String actorId) {
        if (userIds == null || userIds.isEmpty()) return;
        String actorName = actorName(actorId);
        for (String userId : userIds) {
            try {
                create(userId, type, groupId, chatId, groupName, actorId, actorName);
            } catch (Exception e) {
                log.warn("Could not notify {} ({} in group {}): {}", userId, type, groupId, e.getMessage());
            }
        }
    }

    public void notifyUser(String userId, NotificationType type, String groupId, String chatId,
                           String groupName, String actorId) {
        notifyEach(List.of(userId), type, groupId, chatId, groupName, actorId);
    }

    private void create(String userId, NotificationType type, String groupId, String chatId,
                        String groupName, String actorId, String actorName) {
        if (userId == null || userId.isBlank() || userId.equals(actorId)) return;

        String group = groupName == null || groupName.isBlank() ? "a group" : groupName.trim();
        String title = titleFor(type);
        String body = bodyFor(type, actorName, group);

        UserNotification n = new UserNotification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setGroupId(groupId);
        n.setChatId(chatId);
        n.setActorId(actorId);
        n.setActorName(actorName);
        n.setCreatedAt(LocalDateTime.now());
        UserNotification saved = repository.save(n);

        redisMessagePublisher.convertAndSendToUser(userId, "/queue/notifications", toResponse(saved));

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Group", group);
        details.put("By", actorName);
        notificationService.emailUser(userId, subjectFor(type, group), title, body, details, "Open messages", "/messages");
    }

    /**
     * Tells the people tagged in a message. Everyone gets a stored notification and a live push. The email goes
     * out unless this person already got a mention email for this chat in the last few minutes (so a burst of
     * tagged messages is one email, not twenty), or an @all in a very large group. The sender is never notified
     * about their own message. Runs in the background so a big group does not slow the send.
     *
     * @param mentionedIds people tagged by name
     * @param everyone     true when the message used @all (the whole group is notified)
     */
    @Async
    public void notifyMention(Chat chat, String senderId, String senderName, String content,
                              Collection<String> mentionedIds, boolean everyone) {
        try {
            Set<String> recipients = new LinkedHashSet<>();
            if (mentionedIds != null) recipients.addAll(mentionedIds);
            if (everyone && chat.getParticipantIds() != null) recipients.addAll(chat.getParticipantIds());
            recipients.remove(senderId);
            recipients.removeIf(id -> id == null || id.isBlank());
            if (recipients.isEmpty()) return;

            boolean group = chat.getGroupId() != null;
            String groupName = group ? groupRepository.findById(chat.getGroupId()).map(g -> g.getName()).orElse(null) : null;
            String where = groupName != null && !groupName.isBlank() ? groupName.trim()
                    : (group ? "a group" : "a private chat");
            String who = senderName == null || senderName.isBlank() ? "Someone" : senderName;
            String preview = preview(content);
            boolean emailAllowedForAll = !everyone || recipients.size() <= mentionAllEmailMaxMembers;
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime windowStart = now.minusMinutes(Math.max(0, mentionEmailCooldownMinutes));
            List<NotificationType> mentionTypes = List.of(NotificationType.MENTIONED, NotificationType.MENTIONED_ALL);

            for (String userId : recipients) {
                try {
                    // Someone named directly is notified as MENTIONED even when the message also used @all.
                    boolean named = mentionedIds != null && mentionedIds.contains(userId);
                    NotificationType type = named || !everyone ? NotificationType.MENTIONED : NotificationType.MENTIONED_ALL;

                    boolean sendEmail = emailAllowedForAll
                            && !repository.existsByUserIdAndChatIdAndTypeInAndEmailedAtAfter(userId, chat.getId(), mentionTypes, windowStart);

                    UserNotification n = new UserNotification();
                    n.setUserId(userId);
                    n.setType(type);
                    n.setTitle(titleFor(type));
                    n.setBody(bodyFor(type, who, where) + (preview.isEmpty() ? "" : " \"" + preview + "\""));
                    n.setGroupId(chat.getGroupId());
                    n.setChatId(chat.getId());
                    n.setActorId(senderId);
                    n.setActorName(who);
                    n.setCreatedAt(now);
                    if (sendEmail) n.setEmailedAt(now);
                    UserNotification saved = repository.save(n);

                    redisMessagePublisher.convertAndSendToUser(userId, "/queue/notifications", toResponse(saved));

                    if (sendEmail) {
                        Map<String, String> details = new LinkedHashMap<>();
                        details.put(group ? "Group" : "Chat", where);
                        details.put("From", who);
                        if (!preview.isEmpty()) details.put("Message", preview);
                        notificationService.emailUser(userId, subjectFor(type, where, who), titleFor(type),
                                bodyFor(type, who, where), details, "Open chat", "/messages/chat/" + chat.getId(), "@");
                    }
                } catch (Exception e) {
                    log.warn("Could not notify {} about a mention in chat {}: {}", userId, chat.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Mention notifications failed for chat {}: {}", chat == null ? null : chat.getId(), e.getMessage());
        }
    }

    /** One line, at most 140 characters, for the notification and the email. */
    private static String preview(String content) {
        if (content == null) return "";
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 140 ? oneLine : oneLine.substring(0, 137).trim() + "...";
    }

    // ------------------------------------------------------------------- read

    public List<NotificationResponse> list(String userId, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIST));
        return repository.findByUserId(userId, PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(UserNotificationService::toResponse).toList();
    }

    public long unreadCount(String userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    public void markRead(String userId, String id) {
        UserNotification n = repository.findById(id)
                .filter(x -> userId.equals(x.getUserId()))
                .orElseThrow(() -> HttpStatusException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found"));
        if (n.isRead()) return;
        n.setRead(true);
        n.setReadAt(LocalDateTime.now());
        repository.save(n);
    }

    public long markAllRead(String userId) {
        return mongoTemplate.updateMulti(
                Query.query(Criteria.where("userId").is(userId).and("read").is(false)),
                new Update().set("read", true).set("readAt", LocalDateTime.now()),
                UserNotification.class).getModifiedCount();
    }

    public void delete(String userId, String id) {
        UserNotification n = repository.findById(id)
                .filter(x -> userId.equals(x.getUserId()))
                .orElseThrow(() -> HttpStatusException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found"));
        repository.delete(n);
    }

    public long clear(String userId) {
        return repository.deleteByUserId(userId);
    }

    // ---------------------------------------------------------------- helpers

    private static NotificationResponse toResponse(UserNotification n) {
        NotificationResponse r = new NotificationResponse();
        r.setId(n.getId());
        r.setType(n.getType());
        r.setTitle(n.getTitle());
        r.setBody(n.getBody());
        r.setGroupId(n.getGroupId());
        r.setChatId(n.getChatId());
        r.setActorId(n.getActorId());
        r.setActorName(n.getActorName());
        r.setCreatedAt(n.getCreatedAt());
        r.setRead(n.isRead());
        return r;
    }

    private static String titleFor(NotificationType type) {
        return switch (type) {
            case MADE_ADMIN -> "You are now an admin";
            case REMOVED_ADMIN -> "You are no longer an admin";
            case BECAME_OWNER -> "You are now the owner";
            case ADDED_TO_GROUP -> "You were added to a group";
            case REMOVED_FROM_GROUP -> "You were removed from a group";
            case MENTIONED -> "You were mentioned";
            case MENTIONED_ALL -> "Everyone was mentioned";
        };
    }

    private static String bodyFor(NotificationType type, String actor, String group) {
        return switch (type) {
            case MADE_ADMIN -> actor + " made you an admin of " + group + ".";
            case REMOVED_ADMIN -> actor + " removed your admin role in " + group + ".";
            case BECAME_OWNER -> actor + " made you the owner of " + group + ".";
            case ADDED_TO_GROUP -> actor + " added you to " + group + ".";
            case REMOVED_FROM_GROUP -> actor + " removed you from " + group + ".";
            case MENTIONED -> actor + " mentioned you in " + group + ".";
            case MENTIONED_ALL -> actor + " mentioned everyone in " + group + ".";
        };
    }

    private static String subjectFor(NotificationType type, String group) {
        return subjectFor(type, group, "Someone");
    }

    private static String subjectFor(NotificationType type, String group, String actor) {
        return switch (type) {
            case MADE_ADMIN -> "You are now an admin of " + group;
            case REMOVED_ADMIN -> "Your admin role in " + group + " was removed";
            case BECAME_OWNER -> "You are now the owner of " + group;
            case ADDED_TO_GROUP -> "You were added to " + group;
            case REMOVED_FROM_GROUP -> "You were removed from " + group;
            case MENTIONED -> actor + " mentioned you in " + group;
            case MENTIONED_ALL -> actor + " mentioned everyone in " + group;
        };
    }

    /**
     * The name of the person who did it. The group actions run inside the actor's own request, so their token
     * is read from it. Outside a request, or when the lookup fails, the notification says "Someone".
     */
    private String actorName(String actorId) {
        if (actorId == null) return "Someone";
        try {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return "Someone";
            String token = attrs.getRequest().getHeader("Authorization");
            if (token == null || !token.startsWith("Bearer ")) return "Someone";
            String name = UserNames.full(authServiceClient.getUserSummary(actorId, token));
            return "Unknown User".equals(name) ? "Someone" : name;
        } catch (Exception e) {
            return "Someone";
        }
    }
}
