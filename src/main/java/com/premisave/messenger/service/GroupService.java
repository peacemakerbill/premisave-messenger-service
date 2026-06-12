package com.premisave.messenger.service;

import com.premisave.messenger.entity.Group;
import com.premisave.messenger.entity.GroupMember;
import com.premisave.messenger.repository.GroupRepository;
import com.premisave.messenger.repository.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;

    public Group createGroup(String name, String description, String adminId) {
        Group group = new Group();
        group.setName(name);
        group.setDescription(description);
        group.setAdminId(adminId);
        group.setCreatedAt(LocalDateTime.now());
        group.getMemberIds().add(adminId);

        Group saved = groupRepository.save(group);

        // Add admin as first member
        GroupMember member = new GroupMember();
        member.setGroupId(saved.getId());
        member.setUserId(adminId);
        member.setRole("ADMIN");
        member.setJoinedAt(LocalDateTime.now());
        groupMemberRepository.save(member);

        log.info("Group created: {} by user {}", name, adminId);
        return saved;
    }

    public void addMemberToGroup(String groupId, String userId, String addedBy) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        if (!group.getAdminId().equals(addedBy)) {
            throw new RuntimeException("Only admin can add members");
        }

        if (group.getMemberIds().contains(userId)) {
            throw new RuntimeException("User is already in the group");
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

    public List<Group> getUserGroups(String userId) {
        return groupRepository.findByMemberIdsContaining(userId);
    }
}