package com.premisave.messenger.controller;

import com.premisave.messenger.entity.Group;
import com.premisave.messenger.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /**
     * Create New Group
     */
    @PostMapping
    public ResponseEntity<Group> createGroup(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            Authentication authentication) {

        String adminId = authentication.getName();
        Group group = groupService.createGroup(name, description, adminId);
        return ResponseEntity.ok(group);
    }

    /**
     * Add Member to Group
     */
    @PostMapping("/{groupId}/members")
    public ResponseEntity<String> addMember(
            @PathVariable String groupId,
            @RequestParam String userId,
            Authentication authentication) {

        String adminId = authentication.getName();
        groupService.addMemberToGroup(groupId, userId, adminId);
        return ResponseEntity.ok("Member added successfully");
    }

    /**
     * Remove Member from Group
     */
    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<String> removeMember(
            @PathVariable String groupId,
            @PathVariable String userId,
            Authentication authentication) {

        String removedBy = authentication.getName();
        groupService.removeMemberFromGroup(groupId, userId, removedBy);
        return ResponseEntity.ok("Member removed successfully");
    }

    /**
     * Leave Group (Current User)
     */
    @PostMapping("/{groupId}/leave")
    public ResponseEntity<String> leaveGroup(
            @PathVariable String groupId,
            Authentication authentication) {

        String userId = authentication.getName();
        groupService.removeMemberFromGroup(groupId, userId, userId);
        return ResponseEntity.ok("You have left the group");
    }

    /**
     * Update Group Description (Admin Only)
     */
    @PutMapping("/{groupId}/description")
    public ResponseEntity<Group> updateGroupDescription(
            @PathVariable String groupId,
            @RequestParam String description,
            Authentication authentication) {

        String adminId = authentication.getName();
        Group updatedGroup = groupService.updateGroupDescription(groupId, description, adminId);
        return ResponseEntity.ok(updatedGroup);
    }

    /**
     * Update Group Photo
     */
    @PostMapping("/{groupId}/photo")
    public ResponseEntity<Group> uploadGroupPhoto(
            @PathVariable String groupId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        String adminId = authentication.getName();
        Group updatedGroup = groupService.updateGroupPhoto(groupId, file, adminId);
        return ResponseEntity.ok(updatedGroup);
    }

    /**
     * Transfer Admin Rights
     */
    @PostMapping("/{groupId}/transfer-admin")
    public ResponseEntity<Group> transferAdmin(
            @PathVariable String groupId,
            @RequestParam String newAdminId,
            Authentication authentication) {

        String currentAdmin = authentication.getName();
        Group updatedGroup = groupService.transferAdmin(groupId, newAdminId, currentAdmin);
        return ResponseEntity.ok(updatedGroup);
    }

    /**
     * Get My Groups
     */
    @GetMapping("/my-groups")
    public ResponseEntity<List<Group>> getMyGroups(Authentication authentication) {
        String userId = authentication.getName();
        List<Group> groups = groupService.getUserGroups(userId);
        return ResponseEntity.ok(groups);
    }

    /**
     * Get Group Members
     */
    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<String>> getGroupMembers(@PathVariable String groupId) {
        List<String> members = groupService.getGroupMemberIds(groupId);
        return ResponseEntity.ok(members);
    }
}