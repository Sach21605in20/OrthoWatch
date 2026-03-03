package com.orthowatch.controller;

import com.orthowatch.dto.WoundImageResponse;
import com.orthowatch.model.User;
import com.orthowatch.model.WoundImage;
import com.orthowatch.repository.UserRepository;
import com.orthowatch.service.AuditService;
import com.orthowatch.service.ImageStorageService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for wound image upload and retrieval.
 *
 * <p>Upload creates a WoundImage record and stores the file. Download serves the image bytes and
 * logs a VIEW_IMAGE audit entry.
 */
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageController {

  private final ImageStorageService imageStorageService;
  private final AuditService auditService;
  private final UserRepository userRepository;

  /**
   * Upload a wound image.
   *
   * @param file the image file (JPEG or PNG, max 10 MB)
   * @param episodeId the episode UUID
   * @param dayNumber post-op day number
   * @param isMandatory whether this is a mandatory image day
   * @param uploadedBy who uploaded: PATIENT or CAREGIVER
   * @return wound image metadata (201 Created)
   */
  @PostMapping("/upload")
  @PreAuthorize("hasAnyRole('SURGEON','NURSE','ADMIN')")
  public ResponseEntity<WoundImageResponse> uploadImage(
      @RequestParam("file") MultipartFile file,
      @RequestParam("episodeId") UUID episodeId,
      @RequestParam("dayNumber") int dayNumber,
      @RequestParam(value = "isMandatory", defaultValue = "false") boolean isMandatory,
      @RequestParam(value = "uploadedBy", defaultValue = "PATIENT") String uploadedBy) {

    WoundImageResponse response =
        imageStorageService.store(file, episodeId, dayNumber, isMandatory, uploadedBy);

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * Download a wound image by ID.
   *
   * <p>Serves the image bytes with the correct Content-Type header and logs a VIEW_IMAGE audit
   * entry.
   *
   * @param imageId the wound image UUID
   * @param authentication the authenticated user
   * @return the image file bytes
   */
  @GetMapping("/{imageId}")
  @PreAuthorize("hasAnyRole('SURGEON','NURSE')")
  public ResponseEntity<Resource> downloadImage(
      @PathVariable UUID imageId, Authentication authentication) {

    WoundImage woundImage = imageStorageService.getWoundImage(imageId);
    Resource resource = imageStorageService.loadAsResource(imageId);

    // Log VIEW_IMAGE audit entry
    String email = authentication.getName();
    User user =
        userRepository
            .findByEmail(email)
            .orElse(null);

    if (user != null) {
      auditService.log(
          user,
          woundImage.getEpisode().getId(),
          "VIEW_IMAGE",
          "WOUND_IMAGE",
          imageId,
          null,
          Map.of("dayNumber", woundImage.getDayNumber()),
          null,
          null);
    }

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(woundImage.getContentType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "inline; filename=\"wound_day" + woundImage.getDayNumber() + "." + getExtension(woundImage.getContentType()) + "\"")
        .body(resource);
  }

  private String getExtension(String contentType) {
    return "image/png".equals(contentType) ? "png" : "jpg";
  }
}
