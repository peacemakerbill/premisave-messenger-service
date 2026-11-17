package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.GroupInvitePreviewResponse;
import com.premisave.messenger.dto.response.GroupInviteResponse;
import com.premisave.messenger.dto.response.GroupJoinResponse;
import com.premisave.messenger.entity.GroupInvite;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.GroupInviteService;
import com.premisave.messenger.util.QrCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Group invite links and their QR codes.
 *
 * Owner and admins manage the link under /api/groups/{groupId}/invite.
 * Anyone signed in who holds a code previews and joins under /api/group-invites/{code}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GroupInviteController {

    private final GroupInviteService inviteService;
    private final CurrentUserResolver currentUserResolver;

    private String me(String authHeader) {
        return currentUserResolver.resolve(authHeader).getId();
    }

    // ----------------------------------------------------------------- manage

    /** The group's working link, or 404 INVITE_NOT_FOUND when it has none. Owner and admins only. */
    @GetMapping("/groups/{groupId}/invite")
    public ResponseEntity<GroupInviteResponse> getInvite(
            @PathVariable String groupId,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(inviteService.toResponse(inviteService.current(groupId, me(authHeader))));
    }

    /**
     * Makes a new link and revokes the old one. Leave expiresInHours out for a link that never
     * expires and maxUses out for one that anyone can use any number of times.
     */
    @PostMapping("/groups/{groupId}/invite")
    public ResponseEntity<GroupInviteResponse> createInvite(
            @PathVariable String groupId,
            @RequestParam(required = false) Integer expiresInHours,
            @RequestParam(required = false) Integer maxUses,
            @RequestHeader("Authorization") String authHeader) {

        GroupInvite invite = inviteService.create(groupId, me(authHeader), expiresInHours, maxUses);
        return ResponseEntity.ok(inviteService.toResponse(invite));
    }

    @DeleteMapping("/groups/{groupId}/invite")
    public ResponseEntity<String> revokeInvite(
            @PathVariable String groupId,
            @RequestHeader("Authorization") String authHeader) {

        inviteService.revoke(groupId, me(authHeader));
        return ResponseEntity.ok("Invite link revoked");
    }

    /** The working link as a QR code PNG. size is in pixels, 128 to 1024. Owner and admins only. */
    @GetMapping("/groups/{groupId}/invite/qr")
    public ResponseEntity<byte[]> inviteQr(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "320") int size,
            @RequestHeader("Authorization") String authHeader) {

        GroupInviteResponse invite = inviteService.toResponse(inviteService.current(groupId, me(authHeader)));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(QrCodes.png(invite.getLink(), size));
    }

    // ------------------------------------------------------------------- use

    /** What the link leads to. 404 INVITE_NOT_FOUND, or 410 INVITE_REVOKED, INVITE_EXPIRED, INVITE_FULL. */
    @GetMapping("/group-invites/{code}")
    public ResponseEntity<GroupInvitePreviewResponse> preview(
            @PathVariable String code,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(inviteService.preview(code, me(authHeader)));
    }

    /** Joins the group. Someone already in it gets alreadyMember true and nothing changes. */
    @PostMapping("/group-invites/{code}/join")
    public ResponseEntity<GroupJoinResponse> join(
            @PathVariable String code,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(inviteService.join(code, me(authHeader)));
    }
}