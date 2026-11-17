package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.CallChatRequest;
import com.premisave.messenger.dto.request.CallLockRequest;
import com.premisave.messenger.dto.request.CallRoleRequest;
import com.premisave.messenger.dto.request.CreateCallLinkRequest;
import com.premisave.messenger.dto.request.InviteToCallRequest;
import com.premisave.messenger.dto.request.StartCallRequest;
import com.premisave.messenger.dto.response.CallChatResponse;
import com.premisave.messenger.dto.response.CallInviteResult;
import com.premisave.messenger.dto.response.CallJoinResponse;
import com.premisave.messenger.dto.response.CallLinkPreviewResponse;
import com.premisave.messenger.dto.response.CallResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.CallService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Audio and video calls. Starting, answering and joining all return the media server address and a join token.
 * Everything that happens during a call also arrives live on /user/queue/calls. Every answer is JSON.
 */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallController {

    private final CallService callService;
    private final CurrentUserResolver currentUserResolver;

    private UserSummaryResponse me(String authHeader) {
        return currentUserResolver.resolve(authHeader);
    }

    // ------------------------------------------------------------------ start

    /** Calls the people in a chat. If a call is already running in that chat, joins it instead. */
    @PostMapping
    public ResponseEntity<CallJoinResponse> start(
            @Valid @RequestBody StartCallRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.start(me(authHeader), authHeader, request));
    }

    /** Makes a call link. Nobody rings. Join it yourself with POST /api/calls/{id}/join, then share linkUrl. */
    @PostMapping("/links")
    public ResponseEntity<CallResponse> createLink(
            @RequestBody(required = false) CreateCallLinkRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.createLink(me(authHeader), request == null ? new CreateCallLinkRequest() : request));
    }

    /** What a person sees before joining from a link. */
    @GetMapping("/links/{code}")
    public ResponseEntity<CallLinkPreviewResponse> preview(
            @PathVariable String code,
            @RequestHeader("Authorization") String authHeader) {

        me(authHeader);
        return ResponseEntity.ok(callService.preview(code));
    }

    @PostMapping("/links/{code}/join")
    public ResponseEntity<CallJoinResponse> joinByLink(
            @PathVariable String code,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.joinByLink(me(authHeader), authHeader, code));
    }

    // ------------------------------------------------------------------- read

    /** The signed-in person's calls, newest first. before is the createdAt of the last call you have. limit is 1 to 100. */
    @GetMapping
    public ResponseEntity<List<CallResponse>> history(
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.history(me(authHeader).getId(), limit, before));
    }

    /** The call running in a chat right now, for the join banner. 204 when there is none. */
    @GetMapping("/active")
    public ResponseEntity<CallResponse> active(
            @RequestParam String chatId,
            @RequestHeader("Authorization") String authHeader) {

        return callService.activeInChat(me(authHeader).getId(), chatId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CallResponse> get(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.get(me(authHeader).getId(), id));
    }

    /** Removes one finished call from the signed-in person's history. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Boolean>> hide(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        callService.hide(me(authHeader).getId(), id);
        return ResponseEntity.ok(Map.of("removed", true));
    }

    /** Clears the signed-in person's whole call history. */
    @DeleteMapping
    public ResponseEntity<Map<String, Long>> clearHistory(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(Map.of("removed", callService.clearHistory(me(authHeader).getId())));
    }

    // ------------------------------------------------------- answer and leave

    /** Answers a ringing call, or joins a running one. */
    @PostMapping("/{id}/join")
    public ResponseEntity<CallJoinResponse> join(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.join(me(authHeader), authHeader, id));
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Map<String, Boolean>> decline(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        callService.decline(me(authHeader), id);
        return ResponseEntity.ok(Map.of("declined", true));
    }

    /** Hangs up. */
    @PostMapping("/{id}/leave")
    public ResponseEntity<Map<String, Boolean>> leave(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        callService.leave(me(authHeader).getId(), id);
        return ResponseEntity.ok(Map.of("left", true));
    }

    /** A host ends the call for everyone. */
    @PostMapping("/{id}/end")
    public ResponseEntity<Map<String, Boolean>> end(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        callService.end(me(authHeader), id);
        return ResponseEntity.ok(Map.of("ended", true));
    }

    // ----------------------------------------------------------- add and manage

    /** Adds people to the call and rings them. */
    @PostMapping("/{id}/invite")
    public ResponseEntity<CallInviteResult> invite(
            @PathVariable String id,
            @Valid @RequestBody InviteToCallRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.invite(me(authHeader), authHeader, id, request.getUserIds()));
    }

    @PostMapping("/{id}/participants/{userId}/mute")
    public ResponseEntity<Map<String, Boolean>> mute(
            @PathVariable String id,
            @PathVariable String userId,
            @RequestHeader("Authorization") String authHeader) {

        callService.muteParticipant(me(authHeader), id, userId);
        return ResponseEntity.ok(Map.of("muted", true));
    }

    @PostMapping("/{id}/mute-all")
    public ResponseEntity<Map<String, Boolean>> muteAll(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        callService.muteAll(me(authHeader), id);
        return ResponseEntity.ok(Map.of("muted", true));
    }

    @PostMapping("/{id}/participants/{userId}/remove")
    public ResponseEntity<Map<String, Boolean>> remove(
            @PathVariable String id,
            @PathVariable String userId,
            @RequestHeader("Authorization") String authHeader) {

        callService.removeParticipant(me(authHeader), id, userId);
        return ResponseEntity.ok(Map.of("removed", true));
    }

    /** COHOST, MEMBER, or HOST (hands the host role over). Only the host can. */
    @PostMapping("/{id}/participants/{userId}/role")
    public ResponseEntity<Map<String, Boolean>> role(
            @PathVariable String id,
            @PathVariable String userId,
            @Valid @RequestBody CallRoleRequest request,
            @RequestHeader("Authorization") String authHeader) {

        callService.setRole(me(authHeader), id, userId, request.getRole());
        return ResponseEntity.ok(Map.of("updated", true));
    }

    @PostMapping("/{id}/lock")
    public ResponseEntity<Map<String, Boolean>> lock(
            @PathVariable String id,
            @RequestBody CallLockRequest request,
            @RequestHeader("Authorization") String authHeader) {

        callService.setLocked(me(authHeader), id, request.isLocked());
        return ResponseEntity.ok(Map.of("locked", request.isLocked()));
    }

    // ------------------------------------------------------------- call chat

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<CallChatResponse>> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.chat(me(authHeader), id, limit));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<CallChatResponse> sendMessage(
            @PathVariable String id,
            @Valid @RequestBody CallChatRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(callService.sendChat(me(authHeader), id, request.getText()));
    }
}
