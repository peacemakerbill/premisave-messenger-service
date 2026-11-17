package com.premisave.messenger.service;

import com.premisave.messenger.config.GroupInviteProperties;
import com.premisave.messenger.dto.response.GroupInvitePreviewResponse;
import com.premisave.messenger.dto.response.GroupInviteResponse;
import com.premisave.messenger.dto.response.GroupJoinResponse;
import com.premisave.messenger.entity.Group;
import com.premisave.messenger.entity.GroupInvite;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.repository.GroupInviteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Invite links: a group's owner or admins make one, share it as text or as a
 * QR code, and anyone who opens it can preview the group and join.
 *
 * A group has one working link. Making a new one revokes the old one, which is
 * how an admin takes back a link that leaked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupInviteService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Codes are 24 URL-safe characters; anything else cannot be one of ours. */
    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final GroupInviteRepository inviteRepository;
    private final GroupService groupService;
    private final GroupInviteProperties props;
    private final MongoTemplate mongoTemplate;

    // ----------------------------------------------------------------- manage

    /** Makes a new link for the group and revokes the old one. Owner and admins only. */
    public GroupInvite create(String groupId, String userId, Integer expiresInHours, Integer maxUses) {
        Group group = groupService.requireManager(groupId, userId, "Only a group admin can create an invite link");
        if (group.getMemberIds().isEmpty()) {
            throw HttpStatusException.gone("GROUP_GONE", "This group no longer exists");
        }
        if (expiresInHours != null && (expiresInHours < 1 || expiresInHours > props.getMaxExpiryHours())) {
            throw HttpStatusException.badRequest("expiresInHours must be between 1 and " + props.getMaxExpiryHours() + ", or left out for no expiry");
        }
        if (maxUses != null && (maxUses < 1 || maxUses > props.getMaxUsesLimit())) {
            throw HttpStatusException.badRequest("maxUses must be between 1 and " + props.getMaxUsesLimit() + ", or left out for no limit");
        }

        revokeAll(groupId);

        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 0; ; attempt++) {
            GroupInvite invite = new GroupInvite();
            invite.setCode(newCode());
            invite.setGroupId(groupId);
            invite.setCreatedBy(userId);
            invite.setCreatedAt(now);
            invite.setExpiresAt(expiresInHours == null ? null : now.plusHours(expiresInHours));
            invite.setMaxUses(maxUses);
            try {
                GroupInvite saved = inviteRepository.save(invite);
                log.info("Invite link created for group {} by {} (expires {}, max uses {})", groupId, userId, invite.getExpiresAt(), maxUses);
                return saved;
            } catch (DuplicateKeyException e) {
                // A 144-bit code colliding is not going to happen, but the unique index is the guarantee.
                if (attempt >= 2) throw e;
            }
        }
    }

    /** The group's working link. 404 when there is none. Owner and admins only. */
    public GroupInvite current(String groupId, String userId) {
        groupService.requireManager(groupId, userId, "Only a group admin can see the invite link");
        return inviteRepository.findByGroupIdAndRevokedFalse(groupId).stream()
                .filter(GroupInviteService::isUsable)
                .findFirst()
                .orElseThrow(() -> HttpStatusException.notFound("INVITE_NOT_FOUND", "This group has no invite link yet"));
    }

    public void revoke(String groupId, String userId) {
        groupService.requireManager(groupId, userId, "Only a group admin can revoke the invite link");
        revokeAll(groupId);
        log.info("Invite link revoked for group {} by {}", groupId, userId);
    }

    // ------------------------------------------------------------------- use

    /** What a link leads to. Works for anyone signed in, member or not. */
    public GroupInvitePreviewResponse preview(String code, String userId) {
        GroupInvite invite = requireUsable(code);
        Group group = groupService.findGroup(invite.getGroupId());
        boolean member = group.getMemberIds().contains(userId);

        GroupInvitePreviewResponse response = new GroupInvitePreviewResponse();
        response.setGroupId(group.getId());
        response.setName(group.getName());
        response.setDescription(group.getDescription());
        response.setGroupPhotoUrl(group.getGroupPhotoUrl());
        response.setMemberCount(group.getMemberIds().size());
        response.setAlreadyMember(member);
        response.setChatId(member ? groupService.getChatIdOf(group.getId()) : null);
        response.setExpiresAt(invite.getExpiresAt());
        return response;
    }

    /** Joins the group. A person already in it gets the group back without using up the link. */
    public GroupJoinResponse join(String code, String userId) {
        GroupInvite invite = requireUsable(code);
        Group group = groupService.findGroup(invite.getGroupId());

        boolean already = group.getMemberIds().contains(userId);
        if (!already) {
            consume(invite);
            try {
                group = groupService.joinGroup(group.getId(), userId);
            } catch (RuntimeException e) {
                release(invite);
                throw e;
            }
        }

        GroupJoinResponse response = new GroupJoinResponse();
        response.setGroupId(group.getId());
        response.setChatId(groupService.getChatIdOf(group.getId()));
        response.setName(group.getName());
        response.setAlreadyMember(already);
        return response;
    }

    public GroupInviteResponse toResponse(GroupInvite invite) {
        GroupInviteResponse response = new GroupInviteResponse();
        response.setGroupId(invite.getGroupId());
        response.setCode(invite.getCode());
        response.setLink(props.linkFor(invite.getCode()));
        response.setCreatedBy(invite.getCreatedBy());
        response.setCreatedAt(invite.getCreatedAt());
        response.setExpiresAt(invite.getExpiresAt());
        response.setMaxUses(invite.getMaxUses());
        response.setUses(invite.getUses());
        return response;
    }

    // ---------------------------------------------------------------- helpers

    private GroupInvite requireUsable(String code) {
        if (code == null || !CODE_SHAPE.matcher(code).matches()) {
            throw HttpStatusException.notFound("INVITE_NOT_FOUND", "This invite link is not valid");
        }
        GroupInvite invite = inviteRepository.findByCode(code)
                .orElseThrow(() -> HttpStatusException.notFound("INVITE_NOT_FOUND", "This invite link is not valid"));
        if (invite.isRevoked()) {
            throw HttpStatusException.gone("INVITE_REVOKED", "This invite link was reset or revoked. Ask an admin for a new one");
        }
        if (isExpired(invite)) {
            throw HttpStatusException.gone("INVITE_EXPIRED", "This invite link has expired. Ask an admin for a new one");
        }
        if (isFull(invite)) {
            throw HttpStatusException.gone("INVITE_FULL", "This invite link has reached its limit. Ask an admin for a new one");
        }
        return invite;
    }

    /** Counts one join. The update only matches while the link is unrevoked and under its limit, so two people cannot take the last place together. */
    private void consume(GroupInvite invite) {
        Criteria match = Criteria.where("id").is(invite.getId()).and("revoked").is(false);
        if (invite.getMaxUses() != null) match = match.and("uses").lt(invite.getMaxUses());
        GroupInvite updated = mongoTemplate.findAndModify(
                Query.query(match),
                new Update().inc("uses", 1),
                FindAndModifyOptions.options().returnNew(true),
                GroupInvite.class);
        if (updated == null) {
            boolean revoked = inviteRepository.findById(invite.getId()).map(GroupInvite::isRevoked).orElse(true);
            throw revoked
                    ? HttpStatusException.gone("INVITE_REVOKED", "This invite link was reset or revoked. Ask an admin for a new one")
                    : HttpStatusException.gone("INVITE_FULL", "This invite link has reached its limit. Ask an admin for a new one");
        }
    }

    /** Gives a use back when joining failed after it was counted. */
    private void release(GroupInvite invite) {
        try {
            mongoTemplate.updateFirst(Query.query(Criteria.where("id").is(invite.getId())), new Update().inc("uses", -1), GroupInvite.class);
        } catch (RuntimeException e) {
            log.warn("Could not give back a use of invite {}: {}", invite.getId(), e.getMessage());
        }
    }

    private void revokeAll(String groupId) {
        List<GroupInvite> open = inviteRepository.findByGroupIdAndRevokedFalse(groupId);
        if (open.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        open.forEach(i -> {
            i.setRevoked(true);
            i.setRevokedAt(now);
        });
        inviteRepository.saveAll(open);
    }

    private static boolean isUsable(GroupInvite invite) {
        return !invite.isRevoked() && !isExpired(invite) && !isFull(invite);
    }

    private static boolean isExpired(GroupInvite invite) {
        return invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private static boolean isFull(GroupInvite invite) {
        return invite.getMaxUses() != null && invite.getUses() >= invite.getMaxUses();
    }

    private static String newCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}