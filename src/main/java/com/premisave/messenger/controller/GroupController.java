package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.GroupDetailsResponse;
import com.premisave.messenger.util.UserNames;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.entity.Group;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.GroupService;
import com.premisave.messenger.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Every endpoint resolves the caller through auth-service: the JWT subject is
 * an email, but group membership and chats are keyed by the real user id.
 */
@Slf4j
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final UserService userService;
    private final CurrentUserResolver currentUserResolver;

    private String me(String authHeader) {
        return currentUserResolver.resolve(authHeader).getId();
    }

    /** Create a group. memberIds is optional; the caller becomes the owner. */
    @PostMapping
    public ResponseEntity<GroupDetailsResponse> createGroup(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) List<String> memberIds,
            @RequestHeader("Authorization") String authHeader) {

        Group group = groupService.createGroup(name, description, me(authHeader), memberIds);
        return ResponseEntity.ok(details(group, authHeader));
    }

    /** One group with its chat id and members. Members only. */
    @GetMapping("/{groupId}")
    public ResponseEntity<GroupDetailsResponse> getGroup(
            @PathVariable String groupId,
            @RequestHeader("Authorization") String authHeader) {

        Group group = groupService.getGroupFor(groupId, me(authHeader));
        return ResponseEntity.ok(details(group, authHeader));
    }

    @GetMapping("/my-groups")
    public ResponseEntity<List<Group>> getMyGroups(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(groupService.getUserGroups(me(authHeader)));
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<String>> getGroupMembers(
            @PathVariable String groupId,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(groupService.getGroupFor(groupId, me(authHeader)).getMemberIds());
    }

    // ---------------------------------------------------------------- members

    @PostMapping("/{groupId}/members/bulk")
    public ResponseEntity<String> addMembersBulk(
            @PathVariable String groupId,
            @RequestBody List<String> userIds,
            @RequestHeader("Authorization") String authHeader) {

        groupService.addMembersToGroup(groupId, userIds, me(authHeader));
        return ResponseEntity.ok("Members added successfully");
    }

    @DeleteMapping("/{groupId}/members/bulk")
    public ResponseEntity<String> removeMembersBulk(
            @PathVariable String groupId,
            @RequestBody List<String> userIds,
            @RequestHeader("Authorization") String authHeader) {

        groupService.removeMembersFromGroup(groupId, userIds, me(authHeader));
        return ResponseEntity.ok("Members removed successfully");
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<String> addMember(
            @PathVariable String groupId,
            @RequestParam String userId,
            @RequestHeader("Authorization") String authHeader) {

        groupService.addMemberToGroup(groupId, userId, me(authHeader));
        return ResponseEntity.ok("Member added successfully");
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<String> removeMember(
            @PathVariable String groupId,
            @PathVariable String userId,
            @RequestHeader("Authorization") String authHeader) {

        groupService.removeMemberFromGroup(groupId, userId, me(authHeader));
        return ResponseEntity.ok("Member removed successfully");
    }

    @PostMapping("/{groupId}/leave")
    public ResponseEntity<String> leaveGroup(
            @PathVariable String groupId,
            @RequestHeader("Authorization") String authHeader) {

        String userId = me(authHeader);
        groupService.removeMemberFromGroup(groupId, userId, userId);
        return ResponseEntity.ok("You have left the group");
    }

    // ----------------------------------------------------------------- admins

    @PostMapping("/{groupId}/admins/bulk")
    public ResponseEntity<String> addAdminsBulk(
            @PathVariable String groupId,
            @RequestBody List<String> userIds,
            @RequestHeader("Authorization") String authHeader) {

        groupService.addAdminsToGroup(groupId, userIds, me(authHeader));
        return ResponseEntity.ok("Users promoted to admin successfully");
    }

    @DeleteMapping("/{groupId}/admins/bulk")
    public ResponseEntity<String> removeAdminsBulk(
            @PathVariable String groupId,
            @RequestBody List<String> userIds,
            @RequestHeader("Authorization") String authHeader) {

        groupService.removeAdminsFromGroup(groupId, userIds, me(authHeader));
        return ResponseEntity.ok("Admins demoted successfully");
    }

    @PostMapping("/{groupId}/admins")
    public ResponseEntity<String> addAdmin(
            @PathVariable String groupId,
            @RequestParam String userId,
            @RequestHeader("Authorization") String authHeader) {

        groupService.addAdminToGroup(groupId, userId, me(authHeader));
        return ResponseEntity.ok("User promoted to admin successfully");
    }

    @DeleteMapping("/{groupId}/admins/{userId}")
    public ResponseEntity<String> removeAdmin(
            @PathVariable String groupId,
            @PathVariable String userId,
            @RequestHeader("Authorization") String authHeader) {

        groupService.removeAdminFromGroup(groupId, userId, me(authHeader));
        return ResponseEntity.ok("User demoted from admin successfully");
    }

    @PostMapping("/{groupId}/transfer-admin")
    public ResponseEntity<Group> transferAdmin(
            @PathVariable String groupId,
            @RequestParam String newAdminId,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(groupService.transferAdmin(groupId, newAdminId, me(authHeader)));
    }

    // ---------------------------------------------------------------- details

    @PutMapping("/{groupId}/name")
    public ResponseEntity<Group> updateGroupName(
            @PathVariable String groupId,
            @RequestParam String name,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(groupService.updateGroupName(groupId, name, me(authHeader)));
    }

    @PutMapping("/{groupId}/description")
    public ResponseEntity<Group> updateGroupDescription(
            @PathVariable String groupId,
            @RequestParam String description,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(groupService.updateGroupDescription(groupId, description, me(authHeader)));
    }

    @PostMapping("/{groupId}/photo")
    public ResponseEntity<Group> uploadGroupPhoto(
            @PathVariable String groupId,
            @RequestParam MultipartFile file,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(groupService.updateGroupPhoto(groupId, file, me(authHeader)));
    }

    // ---------------------------------------------------------------- mapping

    private GroupDetailsResponse details(Group group, String authHeader) {
        GroupDetailsResponse response = new GroupDetailsResponse();
        response.setId(group.getId());
        response.setChatId(groupService.getChatIdOf(group.getId()));
        response.setName(group.getName());
        response.setDescription(group.getDescription());
        response.setGroupPhotoUrl(group.getGroupPhotoUrl());
        response.setAdminId(group.getAdminId());
        response.setModerators(group.getModerators());
        response.setMemberIds(group.getMemberIds());
        response.setCreatedAt(group.getCreatedAt());
        response.setUpdatedAt(group.getUpdatedAt());

        for (String memberId : group.getMemberIds()) {
            UserSummaryResponse user = userService.getUserSummary(memberId, authHeader);
            GroupDetailsResponse.Member member = new GroupDetailsResponse.Member();
            member.setId(memberId);
            member.setDisplayName(UserNames.full(user));
            member.setUsername(user.getUsername());
            member.setEmail(user.getEmail());
            member.setFirstName(user.getFirstName());
            member.setLastName(user.getLastName());
            member.setProfilePictureUrl(user.getProfilePictureUrl());
            member.setRole(memberId.equals(group.getAdminId()) ? "OWNER"
                    : group.getModerators().contains(memberId) ? "ADMIN" : "MEMBER");
            response.getMembers().add(member);
        }
        return response;
    }
}