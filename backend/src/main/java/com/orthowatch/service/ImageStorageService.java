package com.orthowatch.service;

import com.orthowatch.config.StorageProperties;
import com.orthowatch.dto.WoundImageResponse;
import com.orthowatch.exception.InvalidFileException;
import com.orthowatch.exception.ResourceNotFoundException;
import com.orthowatch.model.Episode;
import com.orthowatch.model.WoundImage;
import com.orthowatch.repository.EpisodeRepository;
import com.orthowatch.repository.WoundImageRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service for storing and retrieving wound images.
 *
 * <p>Uses a profile-based storage strategy: LOCAL filesystem for dev, Supabase Storage for pilot.
 * Only LOCAL strategy is implemented for Phase 4.0.
 */
@Service
@RequiredArgsConstructor
public class ImageStorageService {

  private static final Logger logger = LoggerFactory.getLogger(ImageStorageService.class);

  private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

  private static final long RETENTION_YEARS = 3;

  private final StorageProperties storageProperties;
  private final WoundImageRepository woundImageRepository;
  private final EpisodeRepository episodeRepository;

  /**
   * Store a wound image file and create the corresponding database record.
   *
   * @param file the multipart file to store
   * @param episodeId the episode this image belongs to
   * @param dayNumber the post-op day number
   * @param isMandatory whether this is a mandatory image day (Day 3 or 5)
   * @param uploadedBy who uploaded: PATIENT or CAREGIVER
   * @return wound image metadata response
   */
  @Transactional
  public WoundImageResponse store(
      MultipartFile file,
      UUID episodeId,
      int dayNumber,
      boolean isMandatory,
      String uploadedBy) {

    validateFile(file);

    Episode episode =
        episodeRepository
            .findById(episodeId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Episode not found: " + episodeId));

    String extension = getExtension(file.getContentType());
    String filename = dayNumber + "_" + UUID.randomUUID() + "." + extension;
    Path episodeDir = Paths.get(storageProperties.getLocalPath(), episodeId.toString());
    Path targetPath = episodeDir.resolve(filename);

    try {
      Files.createDirectories(episodeDir);
      try (InputStream inputStream = file.getInputStream()) {
        Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to store wound image: " + e.getMessage(), e);
    }

    WoundImage woundImage =
        WoundImage.builder()
            .episode(episode)
            .dayNumber(dayNumber)
            .storagePath(targetPath.toString())
            .storageProvider("LOCAL")
            .fileSizeBytes(file.getSize())
            .contentType(file.getContentType())
            .isMandatory(isMandatory)
            .encrypted(false)
            .retentionExpiresAt(OffsetDateTime.now().plusYears(RETENTION_YEARS))
            .uploadedBy(uploadedBy)
            .build();

    WoundImage saved = woundImageRepository.save(woundImage);

    logger.info(
        "Stored wound image: id={}, episode={}, day={}, size={} bytes",
        saved.getId(),
        episodeId,
        dayNumber,
        file.getSize());

    return toResponse(saved);
  }

  /**
   * Load a wound image as a Spring Resource for streaming.
   *
   * @param imageId the wound image UUID
   * @return the image file as a Resource
   */
  public Resource loadAsResource(UUID imageId) {
    WoundImage woundImage =
        woundImageRepository
            .findById(imageId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Wound image not found: " + imageId));

    try {
      Path filePath = Paths.get(woundImage.getStoragePath());
      Resource resource = new UrlResource(filePath.toUri());

      if (!resource.exists() || !resource.isReadable()) {
        throw new ResourceNotFoundException(
            "Wound image file not found on disk: " + imageId);
      }

      return resource;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load wound image: " + e.getMessage(), e);
    }
  }

  /**
   * Get wound image metadata by ID.
   *
   * @param imageId the wound image UUID
   * @return the wound image entity
   */
  public WoundImage getWoundImage(UUID imageId) {
    return woundImageRepository
        .findById(imageId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Wound image not found: " + imageId));
  }

  private void validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new InvalidFileException("File is empty or missing");
    }

    String contentType = file.getContentType();
    if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new InvalidFileException(
          "Invalid file type: "
              + contentType
              + ". Only image/jpeg and image/png are accepted.");
    }

    if (file.getSize() > storageProperties.getMaxFileSize()) {
      throw new InvalidFileException(
          "File size "
              + file.getSize()
              + " bytes exceeds maximum allowed size of "
              + storageProperties.getMaxFileSize()
              + " bytes (10 MB).");
    }
  }

  private String getExtension(String contentType) {
    return "image/png".equals(contentType) ? "png" : "jpg";
  }

  private WoundImageResponse toResponse(WoundImage image) {
    return WoundImageResponse.builder()
        .id(image.getId())
        .episodeId(image.getEpisode().getId())
        .dayNumber(image.getDayNumber())
        .contentType(image.getContentType())
        .fileSizeBytes(image.getFileSizeBytes())
        .isMandatory(image.isMandatory())
        .uploadedBy(image.getUploadedBy())
        .createdAt(image.getCreatedAt())
        .build();
  }
}
