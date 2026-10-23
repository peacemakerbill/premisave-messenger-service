package com.premisave.messenger.service;

import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.Group;
import com.premisave.messenger.entity.GroupMember;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.ChatRepository;
import com.premisave.messenger.repository.GroupMemberRepository;
import com.premisave.messenger.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups and their chats.
 *
 * Every group has one Chat whose participantIds mirror the group's memberIds.
 * Chat lists, message delivery and access checks read the chat, so every
 * membership change here updates both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ChatRepository chatRepository;
    private final MediaService mediaService;
    private final RedisMessagePublisher redisMessagePublisher;

    // ---------------------------------------------------------------- create

    /** Creates a group and its chat. memberIds are optional extra members. */
    public Group createGroup(String name, String description, String adminId, List<String> memberIds) {
        if (name == null || name.isBlank()) {
            throw HttpStatusException.badRequest("Group name is required");
        }

        Set<String> members = new LinkedHashSet<>();
        members.add(adminId);
        if (memberIds != null) {
            memberIds.stream().filter(id -> id != null && !id.isBlank()).forEach(members::add);
        }

        Group group = new Group();
        group.setName(name.trim());
        group.setDescription(description);
        group.setAdminId(adminId);
        group.setCreatedAt(LocalDateTime.now());
        group.getMemberIds().addAll(members);
        Group saved = groupRepository.save(group);

        Chat chat = new Chat();
        chat.setChatType(ChatType.GROUP);
        chat.setGroupId(saved.getId());
        chat.getParticipantIds().addAll(members);
        chat.setCreatedAt(LocalDateTime.now());
        chat.setActive(true);
        Chat savedChat = chatRepository.save(chat);

        for (String userId : members) {
            saveMember(saved.getId(), userId, userId.equals(adminId) ? "ADMIN" : "MEMBER");
        }

        log.info("Group '{}' created by {} with {} members (id {})", name, adminId, members.size(), saved.getId());
        members.remove(adminId);
        notifyUsers(members, "ADDED", saved.getId(), savedChat.getId());
        return saved;
    }

    // --------------------------------------------------------------- members

    public void addMembersToGroup(String groupId, List<String> userIds, String addedBy) {
        Group group = requireOwner(groupId, addedBy, "Only the group admin can add members");
        Chat chat = chatOf(groupId);

        Set<String> added = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank() || group.getMemberIds().contains(userId)) continue;
            group.getMemberIds().add(userId);
            if (chat != null && !chat.getParticipantIds().contains(userId)) chat.getParticipantIds().add(userId);
            saveMember(groupId, userId, "MEMBER");
            added.add(userId);
        }

        if (added.isEmpty()) return;
        group.setUpdatedAt(LocalDateTime.now());
        groupRepository.save(group);
        saveChat(chat);
        log.info("Added {} members to group {}", added.size(), groupId);

        notifyUsers(added, "ADDED", groupId, idOf(chat));
        notifyUsers(without(group.getMemberIds(), added), "UPDATED", groupId, idOf(chat));
    }

    public void addMemberToGroup(String groupId, String userId, String addedBy) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> HttpStatusException.notFound("GROUP_NOT_FOUND", "Group not found"));
        if (group.getMemberIds().contains(userId)) {
            throw HttpStatusException.conflict("User is already a member");
        }
        addMembersToGroup(groupId, List.of(userId), addedBy);
    }

    public void removeMembersFromGroup(String groupId, List<String> userIds, String removedBy) {
        Group group = group(groupId);
        boolean isOwner = group.getAdminId().equals(removedBy);
        Chat chat = chatOf(groupId);

        Set<String> removed = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (!isOwner && !userId.equals(removedBy)) {
                throw HttpStatusException.forbidden("Only the group admin or the person themselves can remove a member");
            }
            if (userId.equals(group.getAdminId())) {
                if (group.getMemberIds().size() > 1) {
                    throw HttpStatusException.badRequest("Transfer ownership to another member before the owner leaves");
                }
                deactivate(group, chat);
                removed.add(userId);
                continue;
            }
            if (!group.getMemberIds().remove(userId)) continue;
            group.getModerators().remove(userId);
            if (chat != null) chat.getParticipantIds().remove(userId);
            groupMemberRepository.findByGroupIdAndUserId(groupId, userId).ifPresent(groupMemberRepository::delete);
            removed.add(userId);
        }

        if (removed.isEmpty()) return;
        group.setUpdatedAt(LocalDateTime.now());
        groupRepository.save(group);
        saveChat(chat);
        log.info("Removed {} members from group {}", removed.size(), groupId);

        notifyUsers(removed, "REMOVED", groupId, idOf(chat));
        notifyUsers(without(group.getMemberIds(), removed), "UPDATED", groupId, idOf(chat));
    }

    public void removeMemberFromGroup(String groupId, String userId, String removedBy) {
        removeMembersFromGroup(groupId, List.of(userId), removedBy);
    }

    // ---------------------------------------------------------------- admins

    public void addAdminsToGroup(String groupId, List<String> userIds, String requestedBy) {
        Group group = requireOwner(groupId, requestedBy, "Only the group admin can promote others");

        for (String userId : userIds) {
            if (!group.getMemberIds().contains(userId)) {
                throw HttpStatusException.badRequest("User must be a member first");
            }
            if (!group.getModerators().contains(userId) && !userId.equals(group.getAdminId())) {
                group.getModerators().add(userId);
                setRole(groupId, userId, "ADMIN");
            }
        }
        touch(group);
        log.info("Promoted {} users to admin in group {}", userIds.size(), groupId);
    }

    public void addAdminToGroup(String groupId, String userId, String requestedBy) {
        addAdminsToGroup(groupId, List.of(userId), requestedBy);
    }

    public void removeAdminsFromGroup(String groupId, List<String> userIds, String requestedBy) {
        Group group = requireOwner(groupId, requestedBy, "Only the group admin can demote others");

        for (String userId : userIds) {
            if (group.getAdminId().equals(userId)) {
                throw HttpStatusException.badRequest("The group owner cannot be demoted");
            }
            group.getModerators().remove(userId);
            setRole(groupId, userId, "MEMBER");
        }
        touch(group);
        log.info("Demoted {} admins in group {}", userIds.size(), groupId);
    }

    public void removeAdminFromGroup(String groupId, String userId, String requestedBy) {
        removeAdminsFromGroup(groupId, List.of(userId), requestedBy);
    }

    public Group transferAdmin(String groupId, String newAdminId, String currentAdminId) {
        Group group = requireOwner(groupId, currentAdminId, "Only the current admin can transfer ownership");

        if (!group.getMemberIds().contains(newAdminId)) {
            throw HttpStatusException.badRequest("The new admin must be a group member");
        }

        group.getModerators().remove(newAdminId);
        if (!group.getModerators().contains(currentAdminId)) group.getModerators().add(currentAdminId);
        group.setAdminId(newAdminId);
        setRole(groupId, newAdminId, "ADMIN");
        setRole(groupId, currentAdminId, "ADMIN");
        return touch(group);
    }

    // ---------------------------------------------------------------- details

    public Group updateGroupName(String groupId, String name, String updatedBy) {
        Group group = requireOwner(groupId, updatedBy, "Only the group admin can rename the group");
        if (name == null || name.isBlank()) {
            throw HttpStatusException.badRequest("Group name is required");
        }
        group.setName(name.trim());
        return touch(group);
    }

    public Group updateGroupDescription(String groupId, String newDescription, String updatedBy) {
        Group group = requireOwner(groupId, updatedBy, "Only the group admin can update the description");
        group.setDescription(newDescription);
        return touch(group);
    }

    public Group updateGroupPhoto(String groupId, MultipartFile file, String updatedBy) {
        Group group = requireOwner(groupId, updatedBy, "Only the group admin can update the group photo");

        String oldPhotoUrl = group.getGroupPhotoUrl();
        String photoUrl = mediaService.uploadImage(file, "group-photos");
        group.setGroupPhotoUrl(photoUrl);
        Group saved = touch(group);
        mediaService.deleteByUrl(oldPhotoUrl);
        return saved;
    }

    // ---------------------------------------------------------------- queries

    /** The group, only if userId is a member. */
    public Group getGroupFor(String groupId, String userId) {
        Group group = group(groupId);
        if (!group.getMemberIds().contains(userId)) {
            throw HttpStatusException.forbidden("You are not a member of this group");
        }
        return group;
    }

    public String getChatIdOf(String groupId) {
        return idOf(chatOf(groupId));
    }

    public boolean isUserMemberOfGroup(String groupId, String userId) {
        if (groupId == null || userId == null) return false;
        return groupMemberRepository.findByGroupIdAndUserId(groupId, userId).isPresent();
    }

    public List<Group> getUserGroups(String userId) {
        return groupRepository.findByMemberIdsContaining(userId);
    }

    public List<String> getGroupMemberIds(String groupId) {
        return group(groupId).getMemberIds();
    }

    // ---------------------------------------------------------------- helpers

    private Group group(String groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> HttpStatusException.notFound("GROUP_NOT_FOUND", "Group not found"));
    }

    private Group requireOwner(String groupId, String userId, String message) {
        Group group = group(groupId);
        if (!group.getAdminId().equals(userId)) {
            throw HttpStatusException.forbidden(message);
        }
        return group;
    }

    private Chat chatOf(String groupId) {
        return chatRepository.findByGroupId(groupId).stream().findFirst().orElse(null);
    }

    private static String idOf(Chat chat) {
        return chat == null ? null : chat.getId();
    }

    private void saveChat(Chat chat) {
        if (chat == null) return;
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepository.save(chat);
    }

    private void saveMember(String groupId, String userId, String role) {
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        member.setJoinedAt(LocalDateTime.now());
        groupMemberRepository.save(member);
    }

    private void setRole(String groupId, String userId, String role) {
        groupMemberRepository.findByGroupIdAndUserId(groupId, userId).ifPresent(member -> {
            member.setRole(role);
            groupMemberRepository.save(member);
        });
    }

    /** Saves a group after a change and tells its members to refresh. */
    private Group touch(Group group) {
        group.setUpdatedAt(LocalDateTime.now());
        Group saved = groupRepository.save(group);
        notifyUsers(saved.getMemberIds(), "UPDATED", saved.getId(), getChatIdOf(saved.getId()));
        return saved;
    }

    private void deactivate(Group group, Chat chat) {
        group.getMemberIds().clear();
        group.getModerators().clear();
        group.setActive(false);
        groupMemberRepository.findByGroupId(group.getId()).forEach(groupMemberRepository::delete);
        if (chat != null) {
            chat.getParticipantIds().clear();
            chat.setActive(false);
        }
    }

    private static Set<String> without(Collection<String> all, Collection<String> exclude) {
        Set<String> rest = new LinkedHashSet<>(all);
        rest.removeAll(exclude);
        return rest;
    }

    /** Tells connected users that a group changed. Best effort: a failure never fails the request. */
    private void notifyUsers(Collection<String> userIds, String event, String groupId, String chatId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("event", event);
        payload.put("groupId", groupId);
        payload.put("chatId", chatId);
        for (String userId : userIds) {
            try {
                redisMessagePublisher.convertAndSendToUser(userId, "/queue/groups", payload);
            } catch (Exception e) {
                log.warn("Could not notify {} about group {} ({}): {}", userId, groupId, event, e.getMessage());
            }
        }
    }
}