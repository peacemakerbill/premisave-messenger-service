package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.StorageAttachmentsRequest;
import com.premisave.messenger.dto.request.StorageFolderRequest;
import com.premisave.messenger.dto.request.StorageRecordingsRequest;
import com.premisave.messenger.dto.response.StorageCheckResponse;
import com.premisave.messenger.dto.response.StorageSettingsResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.StorageAdminService;
import com.premisave.messenger.service.StorageSettingsService;
import com.premisave.messenger.storage.FolderBrowser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Where the messenger keeps its files, for administrators only. The .env values apply until an administrator saves a
 * setting here, and from then on the saved value wins. DELETE puts a setting back to the .env value.
 *
 * Folders can only be chosen inside STORAGE_ALLOWED_ROOTS, and every change is tried (created, written to, share mounted)
 * before it is saved. Files saved earlier stay readable, because the old folders are remembered.
 */
@RestController
@RequestMapping("/api/storage-settings")
public class StorageSettingsController {

    private final StorageAdminService admin;
    private final StorageSettingsService settings;
    private final CurrentUserResolver currentUserResolver;

    public StorageSettingsController(StorageAdminService admin, StorageSettingsService settings,
                                     CurrentUserResolver currentUserResolver) {
        this.admin = admin;
        this.settings = settings;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public ResponseEntity<StorageSettingsResponse> get(@RequestHeader("Authorization") String authHeader) {
        requireAdmin(authHeader);
        return ResponseEntity.ok(admin.view());
    }

    // ---------------------------------------------------------------- folder picker

    /** The folders inside {@code path}. Without a path, the folders an administrator may choose from. */
    @GetMapping("/browse")
    public ResponseEntity<FolderBrowser.Listing> browse(
            @RequestParam(required = false) String path,
            @RequestHeader("Authorization") String authHeader) {
        requireAdmin(authHeader);
        return ResponseEntity.ok(admin.browse(path));
    }

    /** Makes a folder from the picker and returns it, ready to open. */
    @PostMapping("/folders")
    public ResponseEntity<FolderBrowser.Listing> createFolder(
            @RequestBody StorageFolderRequest request,
            @RequestHeader("Authorization") String authHeader) {
        requireAdmin(authHeader);
        return ResponseEntity.ok(admin.createFolder(request == null ? null : request.parent(), request == null ? null : request.name()));
    }

    // ---------------------------------------------------------------- attachments, group photos, status media

    /** Tries the settings without saving them. The folder is created and written to. */
    @PostMapping("/attachments/check")
    public ResponseEntity<StorageCheckResponse> checkAttachments(
            @RequestBody StorageAttachmentsRequest request,
            @RequestHeader("Authorization") String authHeader) {
        requireAdmin(authHeader);
        return ResponseEntity.ok(settings.checkAttachments(request));
    }

    @PutMapping("/attachments")
    public ResponseEntity<StorageSettingsResponse> saveAttachments(
            @RequestBody StorageAttachmentsRequest request,
            @RequestHeader("Authorization") String authHeader) {
        UserSummaryResponse me = requireAdmin(authHeader);
        settings.saveAttachments(request, me.getId());
        return ResponseEntity.ok(admin.view());
    }

    @DeleteMapping("/attachments")
    public ResponseEntity<StorageSettingsResponse> resetAttachments(@RequestHeader("Authorization") String authHeader) {
        UserSummaryResponse me = requireAdmin(authHeader);
        settings.resetAttachments(me.getId());
        return ResponseEntity.ok(admin.view());
    }

    // ---------------------------------------------------------------- call recordings

    @PostMapping("/recordings/check")
    public ResponseEntity<StorageCheckResponse> checkRecordings(
            @RequestBody StorageRecordingsRequest request,
            @RequestHeader("Authorization") String authHeader) {
        requireAdmin(authHeader);
        return ResponseEntity.ok(admin.checkRecordings(request));
    }

    @PutMapping("/recordings")
    public ResponseEntity<StorageSettingsResponse> saveRecordings(
            @RequestBody StorageRecordingsRequest request,
            @RequestHeader("Authorization") String authHeader) {
        UserSummaryResponse me = requireAdmin(authHeader);
        return ResponseEntity.ok(admin.saveRecordings(request, me.getId()));
    }

    @DeleteMapping("/recordings")
    public ResponseEntity<StorageSettingsResponse> resetRecordings(@RequestHeader("Authorization") String authHeader) {
        UserSummaryResponse me = requireAdmin(authHeader);
        return ResponseEntity.ok(admin.resetRecordings(me.getId()));
    }

    private UserSummaryResponse requireAdmin(String authHeader) {
        UserSummaryResponse me = currentUserResolver.resolve(authHeader);
        if (!"ADMIN".equalsIgnoreCase(me.getRole())) {
            throw HttpStatusException.forbidden("Only an administrator can see or change the storage settings");
        }
        return me;
    }
}
