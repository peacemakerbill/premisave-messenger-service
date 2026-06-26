package com.premisave.messenger.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private final Cloudinary cloudinary;

    @SuppressWarnings("rawtypes")
	public String uploadMedia(MultipartFile file, String folder) {
        try {
            Map uploadResult = cloudinary.uploader().upload(file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", "premisave/messenger/" + folder,
                            "resource_type", "auto"
                    ));

            return (String) uploadResult.get("secure_url");
        } catch (Exception e) {
            log.error("Failed to upload media", e);
            throw new RuntimeException("Media upload failed", e);
        }
    }

    public void deleteMedia(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
        } catch (Exception e) {
            log.warn("Failed to delete media: {}", publicId);
        }
    }
}