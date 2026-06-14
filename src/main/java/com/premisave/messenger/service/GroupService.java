package com.premisave.messenger.service;

import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.Group;
import com.premisave.messenger.entity.GroupMember;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.repository.ChatRepository;
import com.premisave.messenger.repository.GroupMemberRepository;
import com.premisave.messenger.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ChatRepository chatRepository;
    private final MediaService mediaService;

    /**
     * Create a new group (WhatsApp style)
     */
    public Group createGroup(String name, String description, String adminId) {
        Group group = new Group();
        group.setName(name);
        group.setDescription(description);
        group.setAdminId(adminId);
        group.setCreatedAt(LocalDateTime.now());
        group.getMemberIds().add(adminId);

        Group savedGroup = groupRepository.save(group);

        // Create associated Chat
        Chat groupChat = new Chat();
        groupChat.setChatType(ChatType.GROUP);
        groupChat.setGroupId(savedGroup.getId());
        groupChat.getParticipantIds().add(adminId);
        groupChat.setCreatedAt(LocalDateTime.now());
        groupChat.setActive(true);

        chatRepository.save(groupChat);

        // Add admin as first member
        GroupMember adminMember = new GroupMember();
        adminMember.setGroupId(savedGroup.getId());
        adminMember.setUserId(adminId);
        adminMember.setRole("ADMIN");
        adminMember.setJoinedAt(LocalDateTime.now());
        groupMemberRepository.save(adminMember);

        log.info("Group '{}' created by {} with ID: {}", name, adminId, savedGroup.getId());
        return savedGroup;
    }

    /**
     * Update Group Description (Admin Only)
     */
    public Group updateGroupDescription(String groupId, String newDescription, String updatedBy) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        if (!group.getAdminId().equals(updatedBy)) {
            throw new RuntimeException("Only group admin can update description");
        }

        group.setDescription(newDescription);
        group.setUpdatedAt(LocalDateTime.now());

        Group saved = groupRepository.save(group);
        log.info("Group {} description updated by {}", groupId, updatedBy);
        return saved;
    }

    /**
     * Add member to group (Admin only)
     */
    public void addMemberToGroup(String groupId, String userId, String addedBy) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        if (!group.getAdminId().equals(addedBy)) {
            throw new RuntimeException("Only group admin can add members");
        }

        if (group.getMemberIds().contains(userId)) {
            throw new RuntimeException("User is already a member");
        }

        group.getMemberIds().add(userId);
        groupRepository.save(group);

        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole("MEMBER");
        member.setJoinedAt(LocalDateTime.now());
        groupMemberRepository.save(member);

        log.info("User {} added to group {}", userId, groupId);
    }

    /**
     * Remove member from group
     */
    public void removeMemberFromGroup(String groupId, String userId, String removedBy) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        if (!group.getAdminId().equals(removedBy) && !userId.equals(removedBy)) {
            throw new RuntimeException("Only admin or the user themselves can remove");
        }

        group.getMemberIds().remove(userId);
        groupRepository.save(group);

        groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .ifPresent(groupMemberRepository::delete);

        log.info("User {} removed from group {}", userId, groupId);
    }

    /**
     * Update Group Photo
     */
    public Group updateGroupPhoto(String groupId, MultipartFile file, String updatedBy) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        if (!group.getAdminId().equals(updatedBy)) {
            throw new RuntimeException("Only admin can update group photo");
        }

        String photoUrl = mediaService.uploadMedia(file, "group-photos");
        group.setGroupPhotoUrl(photoUrl);
        group.setUpdatedAt(LocalDateTime.now());

        return groupRepository.save(group);
    }

    /**
     * Transfer Admin Rights
     */
    public Group transferAdmin(String groupId, String newAdminId, String currentAdminId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        if (!group.getAdminId().equals(currentAdminId)) {
            throw new RuntimeException("Only current admin can transfer rights");
        }

        if (!group.getMemberIds().contains(newAdminId)) {
            throw new RuntimeException("New admin must be a group member");
        }

        group.setAdminId(newAdminId);
        group.setUpdatedAt(LocalDateTime.now());
        return groupRepository.save(group);
    }

    public List<Group> getUserGroups(String userId) {
        return groupRepository.findByMemberIdsContaining(userId);
    }

    public List<String> getGroupMemberIds(String groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        return group.getMemberIds();
    }
}