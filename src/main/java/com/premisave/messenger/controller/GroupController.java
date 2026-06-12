package com.premisave.messenger.controller;

import com.premisave.messenger.entity.Group;
import com.premisave.messenger.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    public ResponseEntity<Group> createGroup(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            Authentication authentication) {

        String adminId = authentication.getName();
        Group group = groupService.createGroup(name, description, adminId);
        return ResponseEntity.ok(group);
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<String> addMember(
            @PathVariable String groupId,
            @RequestParam String userId,
            Authentication authentication) {

        String adminId = authentication.getName();
        groupService.addMemberToGroup(groupId, userId, adminId);
        return ResponseEntity.ok("Member added successfully");
    }

    @GetMapping("/my-groups")
    public ResponseEntity<List<Group>> getMyGroups(Authentication authentication) {
        String userId = authentication.getName();
        List<Group> groups = groupService.getUserGroups(userId);
        return ResponseEntity.ok(groups);
    }
}