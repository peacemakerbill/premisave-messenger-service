package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.CreateStatusRequest;
import com.premisave.messenger.dto.request.ReactionRequest;
import com.premisave.messenger.dto.request.StatusCommentRequest;
import com.premisave.messenger.dto.response.*;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.StatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Statuses: posts that your followers can watch until they disappear.
 * New statuses are under /feed (people you follow) and /mine. Disappeared ones are under past.
 */
@RestController
@RequestMapping("/api/status")
@RequiredArgsConstructor
public class StatusController {

    private final StatusService statusService;
    private final CurrentUserResolver currentUserResolver;

    private String me(String authHeader) {
        return currentUserResolver.resolve(authHeader).getId();
    }

    /** What the composer needs: the longest a status can stay and the length limits. */
    @GetMapping("/settings")
    public ResponseEntity<StatusSettingsResponse> settings() {
        return ResponseEntity.ok(statusService.settings());
    }

    /**
     * Post a photo, video or audio status, with an optional caption.
     * POST /api/status  (multipart: file, caption, durationHours)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StatusResponse> createWithFile(
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) String backgroundColor,
            @RequestParam(required = false) Integer durationHours,
            @RequestHeader("Authorization") String authHeader) {
        String userId = me(authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                statusService.create(userId, authHeader, text, caption, backgroundColor, durationHours, file));
    }

    /** Post a text status. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatusResponse> createText(
            @RequestBody CreateStatusRequest request,
            @RequestHeader("Authorization") String authHeader) {
        String userId = me(authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(statusService.create(
                userId, authHeader, request.getText(), null, request.getBackgroundColor(), request.getDurationHours(), null));
    }

    /** New statuses: what the people you follow have posted, grouped by person, unseen first. */
    @GetMapping("/feed")
    public ResponseEntity<List<StatusGroupResponse>> feed(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.feed(me(authHeader), authHeader));
    }

    /** Your own statuses that are still showing, with views, likes, reactions and comments. */
    @GetMapping("/mine")
    public ResponseEntity<List<StatusResponse>> mine(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.mine(me(authHeader)));
    }

    /** Your past statuses: the ones that have disappeared, newest first, with everything people did on them. */
    @GetMapping("/mine/past")
    public ResponseEntity<StatusPageResponse> minePast(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("Authorization") String authHeader) {
        String userId = me(authHeader);
        return ResponseEntity.ok(statusService.past(userId, userId, authHeader, page, size));
    }

    /** One person's statuses that are still showing. You must follow them. */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<StatusResponse>> userStatuses(
            @PathVariable String userId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.activeOf(userId, me(authHeader), authHeader));
    }

    /** One person's past statuses. Only they can see them, unless status.past-visible-to-followers is on. */
    @GetMapping("/user/{userId}/past")
    public ResponseEntity<StatusPageResponse> userPast(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.past(userId, me(authHeader), authHeader, page, size));
    }

    @GetMapping("/{statusId}")
    public ResponseEntity<StatusResponse> get(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.get(statusId, me(authHeader), authHeader));
    }

    /** Call when you open a status. Counted once per person; your own views are not counted. */
    @PostMapping("/{statusId}/view")
    public ResponseEntity<Void> view(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        statusService.view(statusId, me(authHeader), authHeader);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{statusId}/like")
    public ResponseEntity<StatusResponse> like(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.like(statusId, me(authHeader), authHeader));
    }

    @DeleteMapping("/{statusId}/like")
    public ResponseEntity<StatusResponse> unlike(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.unlike(statusId, me(authHeader), authHeader));
    }

    /** React with an emoji. One reaction per person; a new one replaces the old. */
    @PutMapping("/{statusId}/reaction")
    public ResponseEntity<StatusResponse> react(
            @PathVariable String statusId,
            @RequestBody ReactionRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.react(statusId, me(authHeader), request.getEmoji(), authHeader));
    }

    @DeleteMapping("/{statusId}/reaction")
    public ResponseEntity<StatusResponse> removeReaction(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.removeReaction(statusId, me(authHeader), authHeader));
    }

    @GetMapping("/{statusId}/comments")
    public ResponseEntity<List<StatusCommentResponse>> comments(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.comments(statusId, me(authHeader), authHeader));
    }

    @PostMapping("/{statusId}/comments")
    public ResponseEntity<StatusCommentResponse> comment(
            @PathVariable String statusId,
            @RequestBody StatusCommentRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(statusService.comment(statusId, me(authHeader), request.getContent(), authHeader));
    }

    /** The author of a comment or the owner of the status can delete it. */
    @DeleteMapping("/{statusId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable String statusId,
            @PathVariable String commentId,
            @RequestHeader("Authorization") String authHeader) {
        statusService.deleteComment(statusId, commentId, me(authHeader));
        return ResponseEntity.noContent().build();
    }

    /** Owner only: who viewed, liked and reacted, reaction totals and the comments. Works for past statuses too. */
    @GetMapping("/{statusId}/insights")
    public ResponseEntity<StatusInsightsResponse> insights(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(statusService.insights(statusId, me(authHeader), authHeader));
    }

    /** Owner only. Deletes the status and everything on it, even after it has disappeared. */
    @DeleteMapping("/{statusId}")
    public ResponseEntity<Void> delete(
            @PathVariable String statusId,
            @RequestHeader("Authorization") String authHeader) {
        statusService.delete(statusId, me(authHeader), authHeader);
        return ResponseEntity.noContent().build();
    }
}