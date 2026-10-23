package com.premisave.messenger.service;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.config.StatusProperties;
import com.premisave.messenger.dto.response.*;
import com.premisave.messenger.entity.Attachment;
import com.premisave.messenger.entity.Status;
import com.premisave.messenger.entity.StatusActivity;
import com.premisave.messenger.entity.StatusComment;
import com.premisave.messenger.enums.AttachmentCategory;
import com.premisave.messenger.enums.StatusActivityKind;
import com.premisave.messenger.enums.StatusType;
import com.premisave.messenger.exception.DownstreamServiceException;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.StatusActivityRepository;
import com.premisave.messenger.repository.StatusCommentRepository;
import com.premisave.messenger.repository.StatusRepository;
import com.premisave.messenger.util.Emojis;
import com.premisave.messenger.util.UserNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Statuses: short posts (text, photo, video or audio) that a person's followers can
 * watch until they disappear.
 *
 * - The owner picks how long a status stays, from 1 hour up to status.max-hours.
 * - Followers (and the owner) can view, like, react and comment while it is showing.
 * - Once it expires it is not deleted. It moves to the owner's past statuses, with
 *   everything people did on it, until the owner deletes it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    // Lists shown to the owner are capped so one very popular status stays quick to open
    private static final int PEOPLE_LIMIT = 200;
    private static final int COMMENT_LIMIT = 100;

    private final StatusRepository statusRepository;
    private final StatusActivityRepository activityRepository;
    private final StatusCommentRepository commentRepository;
    private final MongoTemplate mongoTemplate;
    private final AttachmentService attachmentService;
    private final UserService userService;
    private final AuthServiceClient authServiceClient;
    private final RedisMessagePublisher redisMessagePublisher;
    private final StatusProperties props;

    private enum Access { OWNER, FOLLOWER, NONE }

    public StatusSettingsResponse settings() {
        return new StatusSettingsResponse(props.effectiveMax(), props.effectiveDefault(),
                props.getTextMaxLength(), props.getCommentMaxLength(), props.isPastVisibleToFollowers());
    }

    // ===== POSTING =====

    public StatusResponse create(String ownerId, String authToken, String text, String caption,
                                 String backgroundColor, Integer durationHours, MultipartFile file) {
        int hours = validHours(durationHours);
        boolean hasFile = file != null && !file.isEmpty();
        String cleanText = clean(text);
        String cleanCaption = clean(caption);

        if (!hasFile && cleanText.isEmpty()) {
            throw HttpStatusException.badRequest("A status needs some text, a photo, a video or audio");
        }
        if (cleanText.length() > props.getTextMaxLength() || cleanCaption.length() > props.getTextMaxLength()) {
            throw HttpStatusException.badRequest("Text can be up to " + props.getTextMaxLength() + " characters");
        }

        Status status = new Status();
        status.setOwnerId(ownerId);
        status.setDurationHours(hours);
        LocalDateTime now = LocalDateTime.now();
        status.setCreatedAt(now);
        status.setExpiresAt(now.plusHours(hours));

        Attachment stored = null;
        if (hasFile) {
            stored = attachmentService.storeForStatus(file, ownerId);
            StatusType type = typeOf(stored.getCategory());
            if (type == null) {
                attachmentService.deleteForMessage(stored.getId());
                throw HttpStatusException.badRequest("A status can be a photo, a video, audio or text");
            }
            status.setType(type);
            status.setCaption(cleanCaption.isEmpty() ? cleanText : cleanCaption);
            status.setAttachmentId(stored.getId());
            status.setFileName(stored.getFileName());
            status.setMimeType(stored.getMimeType());
            status.setFileSize(stored.getSize());
        } else {
            status.setType(StatusType.TEXT);
            status.setText(cleanText);
            status.setBackgroundColor(backgroundColor != null && COLOR.matcher(backgroundColor.trim()).matches()
                    ? backgroundColor.trim() : null);
        }

        Status saved;
        try {
            saved = statusRepository.save(status);
        } catch (RuntimeException e) {
            if (stored != null) attachmentService.deleteForMessage(stored.getId());
            throw e;
        }
        log.info("Status {} posted by {} ({}, {}h)", saved.getId(), ownerId, saved.getType(), hours);

        notifyFollowers(ownerId, authToken, StatusEvent.of("NEW_STATUS", saved.getId(), ownerId));
        return toResponses(List.of(saved), ownerId).get(0);
    }

    /** Owner only. Removes the status, everything on it and its file, even after it has expired. */
    public void delete(String statusId, String userId, String authToken) {
        Status status = find(statusId);
        if (!status.getOwnerId().equals(userId)) {
            throw HttpStatusException.forbidden("You can only delete your own statuses");
        }
        boolean wasActive = isActive(status);
        activityRepository.deleteByStatusId(statusId);
        commentRepository.deleteByStatusId(statusId);
        statusRepository.deleteById(statusId);
        attachmentService.deleteForMessage(status.getAttachmentId());
        log.info("Status {} deleted by {}", statusId, userId);
        if (wasActive) {
            notifyFollowers(userId, authToken, StatusEvent.of("STATUS_DELETED", statusId, userId));
        }
    }

    // ===== READING =====

    /** Statuses still showing from the people the caller follows, grouped by person. */
    public List<StatusGroupResponse> feed(String viewerId, String authToken) {
        List<UserSummaryResponse> following;
        try {
            following = authServiceClient.getMyFollowing(authToken);
        } catch (feign.FeignException e) {
            throw new DownstreamServiceException("auth-service", "Could not load the people you follow", e);
        }
        Map<String, UserSummaryResponse> people = new LinkedHashMap<>();
        if (following != null) {
            for (UserSummaryResponse u : following) {
                if (u != null && u.getId() != null && !u.getId().equals(viewerId)) people.put(u.getId(), u);
            }
        }
        if (people.isEmpty()) return List.of();

        List<Status> active = statusRepository
                .findByOwnerIdInAndExpiresAtAfterOrderByCreatedAtAsc(people.keySet(), LocalDateTime.now());
        Map<String, StatusResponse> built = toResponses(active, viewerId).stream()
                .collect(Collectors.toMap(StatusResponse::getId, r -> r, (a, b) -> a, LinkedHashMap::new));

        Map<String, List<StatusResponse>> byOwner = new LinkedHashMap<>();
        for (Status s : active) byOwner.computeIfAbsent(s.getOwnerId(), k -> new ArrayList<>()).add(built.get(s.getId()));

        List<StatusGroupResponse> groups = new ArrayList<>();
        for (Map.Entry<String, List<StatusResponse>> entry : byOwner.entrySet()) {
            StatusGroupResponse group = new StatusGroupResponse();
            group.setUser(people.get(entry.getKey()));
            group.setStatuses(entry.getValue());
            group.setUnseenCount((int) entry.getValue().stream().filter(r -> !r.isViewedByMe()).count());
            group.setLatestAt(entry.getValue().get(entry.getValue().size() - 1).getCreatedAt());
            groups.add(group);
        }
        groups.sort(Comparator.comparing((StatusGroupResponse g) -> g.getUnseenCount() == 0)
                .thenComparing(StatusGroupResponse::getLatestAt, Comparator.reverseOrder()));
        return groups;
    }

    /** The caller's own statuses that are still showing, with their totals. */
    public List<StatusResponse> mine(String userId) {
        return toResponses(statusRepository
                .findByOwnerIdAndExpiresAtAfterOrderByCreatedAtAsc(userId, LocalDateTime.now()), userId);
    }

    /** Someone's statuses that are still showing. Owner and followers only. */
    public List<StatusResponse> activeOf(String ownerId, String viewerId, String authToken) {
        if (accessFor(ownerId, viewerId, authToken) == Access.NONE) {
            throw HttpStatusException.forbidden("Follow this person to see their statuses");
        }
        return toResponses(statusRepository
                .findByOwnerIdAndExpiresAtAfterOrderByCreatedAtAsc(ownerId, LocalDateTime.now()), viewerId);
    }

    /**
     * Statuses that have disappeared, newest first. The owner always sees theirs, with totals.
     * Followers see them only when status.past-visible-to-followers is on.
     */
    public StatusPageResponse past(String ownerId, String viewerId, String authToken, int page, int size) {
        Access access = accessFor(ownerId, viewerId, authToken);
        if (access == Access.NONE || (access == Access.FOLLOWER && !props.isPastVisibleToFollowers())) {
            throw HttpStatusException.forbidden("Past statuses are only visible to their owner");
        }
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        List<Status> rows = statusRepository.findByOwnerIdAndExpiresAtLessThanEqualOrderByCreatedAtDesc(
                ownerId, LocalDateTime.now(), PageRequest.of(safePage, safeSize + 1));
        boolean more = rows.size() > safeSize;
        List<Status> pageRows = more ? rows.subList(0, safeSize) : rows;
        return new StatusPageResponse(toResponses(pageRows, viewerId), safePage, safeSize, more);
    }

    public StatusResponse get(String statusId, String viewerId, String authToken) {
        Status status = find(statusId);
        requireReadable(status, viewerId, authToken);
        StatusResponse response = toResponses(List.of(status), viewerId).get(0);
        response.setOwner(userService.getUserSummary(status.getOwnerId(), authToken));
        return response;
    }

    // ===== VIEWING, LIKING, REACTING =====

    /** Records that the caller opened a status. Counted once per person. The owner is not counted. */
    public void view(String statusId, String viewerId, String authToken) {
        Status status = find(statusId);
        requireActiveAccess(status, viewerId, authToken);
        if (status.getOwnerId().equals(viewerId)) return;
        if (insertOnce(status, viewerId, StatusActivityKind.VIEW, null)) {
            pushActivity(bump(statusId, "viewCount", 1));
        }
    }

    public StatusResponse like(String statusId, String userId, String authToken) {
        Status status = find(statusId);
        requireNotOwner(status, userId);
        requireActiveAccess(status, userId, authToken);
        if (insertOnce(status, userId, StatusActivityKind.LIKE, null)) {
            pushActivity(bump(statusId, "likeCount", 1));
        }
        return toResponses(List.of(find(statusId)), userId).get(0);
    }

    public StatusResponse unlike(String statusId, String userId, String authToken) {
        Status status = find(statusId);
        requireActiveAccess(status, userId, authToken);
        if (removeOnce(statusId, userId, StatusActivityKind.LIKE)) {
            pushActivity(bump(statusId, "likeCount", -1));
        }
        return toResponses(List.of(find(statusId)), userId).get(0);
    }

    /** One emoji reaction per person. Reacting again with a different emoji replaces it. */
    public StatusResponse react(String statusId, String userId, String emoji, String authToken) {
        String clean = Emojis.validate(emoji);
        Status status = find(statusId);
        requireNotOwner(status, userId);
        requireActiveAccess(status, userId, authToken);

        Optional<StatusActivity> existing = activityRepository
                .findByStatusIdAndUserIdAndKind(statusId, userId, StatusActivityKind.REACTION);
        if (existing.isPresent()) {
            changeEmoji(existing.get(), clean);
        } else if (insertOnce(status, userId, StatusActivityKind.REACTION, clean)) {
            pushActivity(bump(statusId, "reactionCount", 1));
        } else {
            // Another request from the same person got in first: replace its emoji
            activityRepository.findByStatusIdAndUserIdAndKind(statusId, userId, StatusActivityKind.REACTION)
                    .ifPresent(a -> changeEmoji(a, clean));
        }
        return toResponses(List.of(find(statusId)), userId).get(0);
    }

    public StatusResponse removeReaction(String statusId, String userId, String authToken) {
        Status status = find(statusId);
        requireActiveAccess(status, userId, authToken);
        if (removeOnce(statusId, userId, StatusActivityKind.REACTION)) {
            pushActivity(bump(statusId, "reactionCount", -1));
        }
        return toResponses(List.of(find(statusId)), userId).get(0);
    }

    private void changeEmoji(StatusActivity activity, String emoji) {
        if (emoji.equals(activity.getEmoji())) return;
        activity.setEmoji(emoji);
        activityRepository.save(activity);
    }

    // ===== COMMENTS =====

    public StatusCommentResponse comment(String statusId, String userId, String content, String authToken) {
        String text = clean(content);
        if (text.isEmpty()) throw HttpStatusException.badRequest("A comment cannot be empty");
        if (text.length() > props.getCommentMaxLength()) {
            throw HttpStatusException.badRequest("Comments can be up to " + props.getCommentMaxLength() + " characters");
        }
        Status status = find(statusId);
        requireActiveAccess(status, userId, authToken);

        StatusComment comment = new StatusComment();
        comment.setStatusId(statusId);
        comment.setAuthorId(userId);
        comment.setContent(text);
        comment.setCreatedAt(LocalDateTime.now());
        StatusComment saved = commentRepository.save(comment);
        pushActivity(bump(statusId, "commentCount", 1));
        return toCommentResponses(List.of(saved), status, userId, authToken).get(0);
    }

    /**
     * Comments, oldest first. Followers read them while the status is showing. After it
     * expires only the owner can read them, as part of the record.
     */
    public List<StatusCommentResponse> comments(String statusId, String viewerId, String authToken) {
        Status status = find(statusId);
        requireReadable(status, viewerId, authToken);
        if (!isActive(status) && !status.getOwnerId().equals(viewerId)) {
            throw HttpStatusException.forbidden("Comments on past statuses are only visible to their owner");
        }
        return toCommentResponses(commentRepository.findByStatusIdOrderByCreatedAtAsc(
                statusId, PageRequest.of(0, COMMENT_LIMIT)), status, viewerId, authToken);
    }

    /** The author or the status owner can delete a comment. */
    public void deleteComment(String statusId, String commentId, String userId) {
        Status status = find(statusId);
        StatusComment comment = commentRepository.findById(commentId)
                .filter(c -> c.getStatusId().equals(statusId))
                .orElseThrow(() -> HttpStatusException.notFound("COMMENT_NOT_FOUND", "Comment not found"));
        if (!comment.getAuthorId().equals(userId) && !status.getOwnerId().equals(userId)) {
            throw HttpStatusException.forbidden("You can only delete your own comments");
        }
        commentRepository.deleteById(commentId);
        pushActivity(bump(statusId, "commentCount", -1));
    }

    // ===== OWNER INSIGHTS =====

    /** Who viewed, liked and reacted, the reaction totals and the comments. Owner only, active or past. */
    public StatusInsightsResponse insights(String statusId, String ownerId, String authToken) {
        Status status = find(statusId);
        if (!status.getOwnerId().equals(ownerId)) {
            throw HttpStatusException.forbidden("Only the owner can see who engaged with a status");
        }
        StatusInsightsResponse out = new StatusInsightsResponse();
        out.setStatus(toResponses(List.of(status), ownerId).get(0));

        List<StatusActivity> views = recent(statusId, StatusActivityKind.VIEW);
        List<StatusActivity> likes = recent(statusId, StatusActivityKind.LIKE);
        List<StatusActivity> reactions = recent(statusId, StatusActivityKind.REACTION);

        Set<String> ids = new HashSet<>();
        for (List<StatusActivity> list : List.of(views, likes, reactions)) list.forEach(a -> ids.add(a.getUserId()));
        Map<String, UserSummaryResponse> users = ids.isEmpty() ? Map.of() : userService.getUsersSummary(ids, authToken);

        out.setViewers(people(views, users, false));
        out.setLikers(people(likes, users, false));
        out.setReactors(people(reactions, users, true));

        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (StatusActivity a : reactions) breakdown.merge(a.getEmoji(), 1L, Long::sum);
        out.setReactionBreakdown(breakdown);

        out.setComments(toCommentResponses(commentRepository.findByStatusIdOrderByCreatedAtAsc(
                statusId, PageRequest.of(0, COMMENT_LIMIT)), status, ownerId, authToken));
        return out;
    }

    private List<StatusActivity> recent(String statusId, StatusActivityKind kind) {
        return activityRepository.findByStatusIdAndKindOrderByCreatedAtDesc(statusId, kind, PageRequest.of(0, PEOPLE_LIMIT));
    }

    private List<StatusPersonResponse> people(List<StatusActivity> rows, Map<String, UserSummaryResponse> users, boolean emoji) {
        return rows.stream()
                .map(a -> new StatusPersonResponse(users.get(a.getUserId()), emoji ? a.getEmoji() : null, a.getCreatedAt()))
                .toList();
    }

    // ===== ACCESS =====

    private Status find(String statusId) {
        return statusRepository.findById(statusId)
                .orElseThrow(() -> HttpStatusException.notFound("STATUS_NOT_FOUND", "Status not found"));
    }

    private boolean isActive(Status status) {
        return status.getExpiresAt() != null && status.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private Access accessFor(String ownerId, String viewerId, String authToken) {
        if (ownerId.equals(viewerId)) return Access.OWNER;
        try {
            var result = authServiceClient.getFollowStatus(ownerId, authToken);
            return result != null && result.isActive() ? Access.FOLLOWER : Access.NONE;
        } catch (RuntimeException e) {
            log.warn("Could not check whether {} follows {}: {}", viewerId, ownerId, e.getMessage());
            return Access.NONE;
        }
    }

    /** Active status: owner or follower. Past status: the owner, or a follower when that is switched on. */
    private void requireReadable(Status status, String viewerId, String authToken) {
        Access access = accessFor(status.getOwnerId(), viewerId, authToken);
        boolean allowed = access == Access.OWNER
                || (access == Access.FOLLOWER && (isActive(status) || props.isPastVisibleToFollowers()));
        if (!allowed) {
            throw HttpStatusException.forbidden("Follow this person to see their statuses");
        }
    }

    /** Interacting needs the status to be showing and the caller to be the owner or a follower. */
    private void requireActiveAccess(Status status, String userId, String authToken) {
        if (!isActive(status)) {
            throw HttpStatusException.badRequest("This status has disappeared");
        }
        if (accessFor(status.getOwnerId(), userId, authToken) == Access.NONE) {
            throw HttpStatusException.forbidden("Follow this person to react to their statuses");
        }
    }

    private void requireNotOwner(Status status, String userId) {
        if (status.getOwnerId().equals(userId)) {
            throw HttpStatusException.badRequest("You cannot react to your own status");
        }
    }

    // ===== ACTIVITY STORAGE =====

    /** Saves one activity. False when this person already had one of this kind. */
    private boolean insertOnce(Status status, String userId, StatusActivityKind kind, String emoji) {
        StatusActivity a = new StatusActivity();
        a.setStatusId(status.getId());
        a.setUserId(userId);
        a.setKind(kind);
        a.setEmoji(emoji);
        a.setCreatedAt(LocalDateTime.now());
        try {
            activityRepository.save(a);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private boolean removeOnce(String statusId, String userId, StatusActivityKind kind) {
        Query q = new Query(Criteria.where("statusId").is(statusId).and("userId").is(userId).and("kind").is(kind));
        return mongoTemplate.remove(q, StatusActivity.class).getDeletedCount() > 0;
    }

    private Status bump(String statusId, String field, long delta) {
        return mongoTemplate.findAndModify(new Query(Criteria.where("_id").is(statusId)),
                new Update().inc(field, delta), FindAndModifyOptions.options().returnNew(true), Status.class);
    }

    // ===== BUILDING RESPONSES =====

    private List<StatusResponse> toResponses(List<Status> statuses, String viewerId) {
        if (statuses.isEmpty()) return List.of();
        Map<String, Map<StatusActivityKind, StatusActivity>> mine = new HashMap<>();
        List<String> ids = statuses.stream().map(Status::getId).toList();
        for (StatusActivity a : activityRepository.findByUserIdAndStatusIdIn(viewerId, ids)) {
            mine.computeIfAbsent(a.getStatusId(), k -> new EnumMap<>(StatusActivityKind.class)).put(a.getKind(), a);
        }
        List<StatusResponse> out = new ArrayList<>();
        for (Status s : statuses) {
            Map<StatusActivityKind, StatusActivity> own = mine.getOrDefault(s.getId(), Map.of());
            StatusResponse r = new StatusResponse();
            r.setId(s.getId());
            r.setOwnerId(s.getOwnerId());
            r.setType(s.getType());
            r.setText(s.getText());
            r.setBackgroundColor(s.getBackgroundColor());
            r.setCaption(s.getCaption());
            r.setAttachmentId(s.getAttachmentId());
            r.setMediaUrl(attachmentService.signedUrl(s.getAttachmentId()));
            r.setFileName(s.getFileName());
            r.setMimeType(s.getMimeType());
            r.setFileSize(s.getFileSize());
            r.setDurationHours(s.getDurationHours());
            r.setCreatedAt(s.getCreatedAt());
            r.setExpiresAt(s.getExpiresAt());
            r.setActive(isActive(s));
            r.setRemainingSeconds(s.getExpiresAt() == null ? 0
                    : Math.max(0, java.time.Duration.between(LocalDateTime.now(), s.getExpiresAt()).getSeconds()));
            boolean owner = s.getOwnerId().equals(viewerId);
            r.setMine(owner);
            r.setViewedByMe(owner || own.containsKey(StatusActivityKind.VIEW));
            r.setLikedByMe(own.containsKey(StatusActivityKind.LIKE));
            StatusActivity reaction = own.get(StatusActivityKind.REACTION);
            r.setMyReaction(reaction == null ? null : reaction.getEmoji());
            if (owner) {
                StatusResponse.Stats stats = new StatusResponse.Stats();
                stats.setViews(s.getViewCount());
                stats.setLikes(s.getLikeCount());
                stats.setReactions(s.getReactionCount());
                stats.setComments(s.getCommentCount());
                r.setStats(stats);
            }
            out.add(r);
        }
        return out;
    }

    private List<StatusCommentResponse> toCommentResponses(List<StatusComment> rows, Status status, String viewerId, String authToken) {
        Set<String> ids = rows.stream().map(StatusComment::getAuthorId).collect(Collectors.toSet());
        Map<String, UserSummaryResponse> authors = ids.isEmpty() ? Map.of() : userService.getUsersSummary(ids, authToken);
        List<StatusCommentResponse> out = new ArrayList<>();
        for (StatusComment c : rows) {
            StatusCommentResponse r = new StatusCommentResponse();
            r.setId(c.getId());
            r.setStatusId(c.getStatusId());
            UserSummaryResponse author = authors.get(c.getAuthorId());
            if (author != null && author.getDisplayName() == null) author.setDisplayName(UserNames.full(author));
            r.setAuthor(author);
            r.setContent(c.getContent());
            r.setCreatedAt(c.getCreatedAt());
            r.setMine(c.getAuthorId().equals(viewerId));
            r.setCanDelete(r.isMine() || status.getOwnerId().equals(viewerId));
            out.add(r);
        }
        return out;
    }

    // ===== PUSHES =====

    private void pushActivity(Status updated) {
        if (updated == null) return;
        StatusEvent event = new StatusEvent("ACTIVITY", updated.getId(), updated.getOwnerId(),
                updated.getViewCount(), updated.getLikeCount(), updated.getReactionCount(), updated.getCommentCount());
        push(updated.getOwnerId(), event);
    }

    /** Tells the owner's followers. Best effort: a failed push never fails the request. */
    private void notifyFollowers(String ownerId, String authToken, StatusEvent event) {
        try {
            List<UserSummaryResponse> followers = authServiceClient.getMyFollowers(authToken);
            if (followers == null) return;
            for (UserSummaryResponse f : followers) {
                if (f != null && f.getId() != null && !f.getId().equals(ownerId)) push(f.getId(), event);
            }
        } catch (RuntimeException e) {
            log.warn("Could not tell followers of {} about {}: {}", ownerId, event.getEvent(), e.getMessage());
        }
    }

    private void push(String userId, StatusEvent event) {
        try {
            redisMessagePublisher.convertAndSendToUser(userId, "/queue/status", event);
        } catch (RuntimeException e) {
            log.warn("Could not send {} to {}: {}", event.getEvent(), userId, e.getMessage());
        }
    }

    // ===== SMALL HELPERS =====

    private int validHours(Integer requested) {
        int max = props.effectiveMax();
        int hours = requested == null ? props.effectiveDefault() : requested;
        if (hours < 1 || hours > max) {
            throw HttpStatusException.badRequest("A status can stay for 1 to " + max + (max == 1 ? " hour" : " hours"));
        }
        return hours;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private StatusType typeOf(AttachmentCategory category) {
        if (category == null) return null;
        return switch (category) {
            case IMAGE -> StatusType.IMAGE;
            case VIDEO -> StatusType.VIDEO;
            case AUDIO -> StatusType.AUDIO;
            default -> null;
        };
    }
}