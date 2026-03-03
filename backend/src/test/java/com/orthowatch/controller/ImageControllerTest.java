package com.orthowatch.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.orthowatch.dto.WoundImageResponse;
import com.orthowatch.exception.GlobalExceptionHandler;
import com.orthowatch.exception.InvalidFileException;
import com.orthowatch.exception.ResourceNotFoundException;
import com.orthowatch.model.Episode;
import com.orthowatch.model.User;
import com.orthowatch.model.WoundImage;
import com.orthowatch.repository.UserRepository;
import com.orthowatch.service.AuditService;
import com.orthowatch.service.ImageStorageService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@ContextConfiguration(classes = {ImageController.class, GlobalExceptionHandler.class})
class ImageControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private ImageStorageService imageStorageService;
  @MockBean private AuditService auditService;
  @MockBean private UserRepository userRepository;

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void uploadImage_validFile_returns201() throws Exception {
    UUID episodeId = UUID.randomUUID();
    UUID imageId = UUID.randomUUID();

    WoundImageResponse response =
        WoundImageResponse.builder()
            .id(imageId)
            .episodeId(episodeId)
            .dayNumber(3)
            .contentType("image/jpeg")
            .fileSizeBytes(1024)
            .isMandatory(true)
            .uploadedBy("PATIENT")
            .createdAt(OffsetDateTime.now())
            .build();

    when(imageStorageService.store(any(), eq(episodeId), eq(3), eq(true), eq("PATIENT")))
        .thenReturn(response);

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "wound.jpg", "image/jpeg", "fake-jpeg".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/images/upload")
                .file(file)
                .param("episodeId", episodeId.toString())
                .param("dayNumber", "3")
                .param("isMandatory", "true")
                .param("uploadedBy", "PATIENT")
                .with(csrf()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(imageId.toString()))
        .andExpect(jsonPath("$.episodeId").value(episodeId.toString()))
        .andExpect(jsonPath("$.dayNumber").value(3))
        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
        .andExpect(jsonPath("$.uploadedBy").value("PATIENT"));
  }

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void uploadImage_invalidContentType_returns400() throws Exception {
    UUID episodeId = UUID.randomUUID();

    when(imageStorageService.store(any(), eq(episodeId), eq(3), eq(false), eq("PATIENT")))
        .thenThrow(
            new InvalidFileException(
                "Invalid file type: image/gif. Only image/jpeg and image/png are accepted."));

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "wound.gif", "image/gif", "fake-gif".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/images/upload")
                .file(file)
                .param("episodeId", episodeId.toString())
                .param("dayNumber", "3")
                .param("uploadedBy", "PATIENT")
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid File"))
        .andExpect(jsonPath("$.message").value(
            "Invalid file type: image/gif. Only image/jpeg and image/png are accepted."));
  }

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void downloadImage_existingImage_returns200WithImageBytes() throws Exception {
    UUID imageId = UUID.randomUUID();
    UUID episodeId = UUID.randomUUID();
    byte[] imageBytes = "fake-image-data".getBytes();

    Episode episode = Episode.builder().id(episodeId).build();
    WoundImage woundImage =
        WoundImage.builder()
            .id(imageId)
            .episode(episode)
            .dayNumber(3)
            .contentType("image/jpeg")
            .storagePath("/fake/path/wound.jpg")
            .storageProvider("LOCAL")
            .build();

    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("surgeon@orthowatch.com")
            .fullName("Dr. Surgeon")
            .role("SURGEON")
            .build();

    when(imageStorageService.getWoundImage(imageId)).thenReturn(woundImage);
    when(imageStorageService.loadAsResource(imageId))
        .thenReturn(new ByteArrayResource(imageBytes));
    when(userRepository.findByEmail("surgeon@orthowatch.com"))
        .thenReturn(Optional.of(user));

    mockMvc
        .perform(get("/api/v1/images/{imageId}", imageId))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/jpeg"))
        .andExpect(content().bytes(imageBytes));
  }

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void downloadImage_nonExistentImage_returns404() throws Exception {
    UUID fakeId = UUID.randomUUID();

    when(imageStorageService.getWoundImage(fakeId))
        .thenThrow(new ResourceNotFoundException("Wound image not found: " + fakeId));

    mockMvc
        .perform(get("/api/v1/images/{imageId}", fakeId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Wound image not found: " + fakeId));
  }
}
