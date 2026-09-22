package com.premisave.messenger.controller;

import com.premisave.messenger.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadMedia(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "images") String type) {

        String url = mediaService.uploadMedia(file, type);
        return ResponseEntity.ok(url);
    }
}