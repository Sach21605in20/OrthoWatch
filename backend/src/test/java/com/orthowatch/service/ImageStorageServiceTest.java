package com.orthowatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.orthowatch.config.StorageProperties;
import com.orthowatch.dto.WoundImageResponse;
import com.orthowatch.exception.InvalidFileException;
import com.orthowatch.exception.ResourceNotFoundException;
import com.orthowatch.model.Episode;
import com.orthowatch.model.WoundImage;
import com.orthowatch.repository.EpisodeRepository;
import com.orthowatch.repository.WoundImageRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ImageStorageServiceTest {

  @Mock private WoundImageRepository woundImageRepository;
  @Mock private EpisodeRepository episodeRepository;

  private StorageProperties storageProperties;

  private ImageStorageService imageStorageService;

  @TempDir Path tempDir;

  private UUID episodeId;
  private Episode episode;

  @BeforeEach
  void setUp() {
    storageProperties = new StorageProperties();
    storageProperties.setLocalPath(tempDir.toString());
    storageProperties.setMaxFileSize(10_485_760L);
    storageProperties.setProvider("LOCAL");

    imageStorageService =
        new ImageStorageService(storageProperties, woundImageRepository, episodeRepository);

    episodeId = UUID.randomUUID();
    episode = Episode.builder().id(episodeId).build();
  }

  @Test
  void store_validJpeg_savesFileAndCreatesEntity() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "wound.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes());

    when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    when(woundImageRepository.save(any(WoundImage.class)))
        .thenAnswer(
            invocation -> {
              WoundImage saved = invocation.getArgument(0);
              saved.setId(UUID.randomUUID());
              return saved;
            });

    WoundImageResponse response =
        imageStorageService.store(file, episodeId, 3, true, "PATIENT");

    assertThat(response).isNotNull();
    assertThat(response.getEpisodeId()).isEqualTo(episodeId);
    assertThat(response.getDayNumber()).isEqualTo(3);
    assertThat(response.getContentType()).isEqualTo("image/jpeg");
    assertThat(response.isMandatory()).isTrue();
    assertThat(response.getUploadedBy()).isEqualTo("PATIENT");

    // Verify file was actually written to disk
    Path episodeDir = tempDir.resolve(episodeId.toString());
    assertThat(Files.exists(episodeDir)).isTrue();
    assertThat(Files.list(episodeDir).count()).isEqualTo(1);
  }

  @Test
  void store_validPng_savesFileAndCreatesEntity() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "wound.png", "image/png", "fake-png-bytes".getBytes());

    when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    when(woundImageRepository.save(any(WoundImage.class)))
        .thenAnswer(
            invocation -> {
              WoundImage saved = invocation.getArgument(0);
              saved.setId(UUID.randomUUID());
              return saved;
            });

    WoundImageResponse response =
        imageStorageService.store(file, episodeId, 5, true, "CAREGIVER");

    assertThat(response).isNotNull();
    assertThat(response.getContentType()).isEqualTo("image/png");
    assertThat(response.getUploadedBy()).isEqualTo("CAREGIVER");

    Path episodeDir = tempDir.resolve(episodeId.toString());
    assertThat(Files.list(episodeDir).count()).isEqualTo(1);
    assertThat(Files.list(episodeDir).findFirst().get().toString()).endsWith(".png");
  }

  @Test
  void store_invalidContentType_throwsInvalidFileException() {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "wound.gif", "image/gif", "fake-gif-bytes".getBytes());

    assertThatThrownBy(
            () -> imageStorageService.store(file, episodeId, 3, false, "PATIENT"))
        .isInstanceOf(InvalidFileException.class)
        .hasMessageContaining("Invalid file type")
        .hasMessageContaining("image/gif");
  }

  @Test
  void store_oversizedFile_throwsInvalidFileException() {
    // Create a file that exceeds the 10 MB limit
    byte[] oversizedContent = new byte[10_485_761]; // 10 MB + 1 byte
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "wound.jpg", "image/jpeg", oversizedContent);

    assertThatThrownBy(
            () -> imageStorageService.store(file, episodeId, 3, false, "PATIENT"))
        .isInstanceOf(InvalidFileException.class)
        .hasMessageContaining("exceeds maximum allowed size");
  }

  @Test
  void loadAsResource_existingImage_returnsResource() throws IOException {
    // Create an actual file on disk
    Path episodeDir = tempDir.resolve(episodeId.toString());
    Files.createDirectories(episodeDir);
    Path imageFile = episodeDir.resolve("3_test.jpg");
    Files.write(imageFile, "fake-image-data".getBytes());

    UUID imageId = UUID.randomUUID();
    WoundImage woundImage =
        WoundImage.builder()
            .id(imageId)
            .episode(episode)
            .storagePath(imageFile.toString())
            .storageProvider("LOCAL")
            .contentType("image/jpeg")
            .build();

    when(woundImageRepository.findById(imageId)).thenReturn(Optional.of(woundImage));

    var resource = imageStorageService.loadAsResource(imageId);

    assertThat(resource).isNotNull();
    assertThat(resource.exists()).isTrue();
    assertThat(resource.isReadable()).isTrue();
    // Close the InputStream explicitly to release the file handle on Windows,
    // otherwise @TempDir cleanup fails with "file is being used by another process".
    try (var inputStream = resource.getInputStream()) {
      assertThat(inputStream.readAllBytes())
          .isEqualTo("fake-image-data".getBytes());
    }
  }

  @Test
  void loadAsResource_nonExistentImage_throwsResourceNotFoundException() {
    UUID fakeId = UUID.randomUUID();
    when(woundImageRepository.findById(fakeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> imageStorageService.loadAsResource(fakeId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Wound image not found");
  }
}
