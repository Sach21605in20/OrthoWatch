package com.orthowatch.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthowatch.dto.AlertResolveRequest;
import com.orthowatch.exception.GlobalExceptionHandler;
import com.orthowatch.exception.ResourceNotFoundException;
import com.orthowatch.model.Alert;
import com.orthowatch.model.Episode;
import com.orthowatch.model.User;
import com.orthowatch.repository.UserRepository;
import com.orthowatch.service.AlertService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest
@ContextConfiguration(classes = {AlertController.class, GlobalExceptionHandler.class})
class AlertControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private AlertService alertService;
  @MockBean private UserRepository userRepository;

  private User mockUser() {
    return User.builder()
        .id(UUID.randomUUID())
        .email("surgeon@orthowatch.com")
        .fullName("Dr. Surgeon")
        .role("SURGEON")
        .build();
  }

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void shouldAcknowledgeAlert() throws Exception {
    User user = mockUser();
    UUID alertId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();

    Episode episode = Episode.builder().id(UUID.randomUUID()).build();
    Alert acknowledged =
        Alert.builder()
            .id(alertId)
            .episode(episode)
            .alertType("HIGH_RISK")
            .severity("HIGH")
            .assignedTo(user)
            .status("ACKNOWLEDGED")
            .acknowledgedAt(now)
            .build();

    when(userRepository.findByEmail("surgeon@orthowatch.com")).thenReturn(Optional.of(user));
    when(alertService.acknowledge(eq(alertId), any(User.class))).thenReturn(acknowledged);

    mockMvc
        .perform(post("/api/v1/alerts/{alertId}/acknowledge", alertId).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alertId").value(alertId.toString()))
        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
        .andExpect(jsonPath("$.acknowledgedAt").exists());
  }

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void shouldResolveAlert() throws Exception {
    User user = mockUser();
    UUID alertId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();

    Episode episode = Episode.builder().id(UUID.randomUUID()).build();
    Alert resolved =
        Alert.builder()
            .id(alertId)
            .episode(episode)
            .alertType("HIGH_RISK")
            .severity("HIGH")
            .assignedTo(user)
            .status("RESOLVED")
            .resolvedAt(now)
            .escalationOutcome("OPD_SCHEDULED")
            .escalationNotes("Visit OPD tomorrow")
            .build();

    AlertResolveRequest request =
        AlertResolveRequest.builder()
            .escalationOutcome("OPD_SCHEDULED")
            .notes("Visit OPD tomorrow")
            .build();

    when(userRepository.findByEmail("surgeon@orthowatch.com")).thenReturn(Optional.of(user));
    when(alertService.resolve(eq(alertId), any(AlertResolveRequest.class), any(User.class)))
        .thenReturn(resolved);

    mockMvc
        .perform(
            post("/api/v1/alerts/{alertId}/resolve", alertId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alertId").value(alertId.toString()))
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.escalationOutcome").value("OPD_SCHEDULED"))
        .andExpect(jsonPath("$.resolvedAt").exists());
  }

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void shouldReturn400WhenEscalationOutcomeInvalid() throws Exception {
    AlertResolveRequest request =
        AlertResolveRequest.builder().escalationOutcome("INVALID_VALUE").build();

    mockMvc
        .perform(
            post("/api/v1/alerts/{alertId}/resolve", UUID.randomUUID())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(username = "surgeon@orthowatch.com", roles = "SURGEON")
  void shouldReturn404WhenAlertNotFound() throws Exception {
    User user = mockUser();
    UUID fakeId = UUID.randomUUID();

    when(userRepository.findByEmail("surgeon@orthowatch.com")).thenReturn(Optional.of(user));
    when(alertService.acknowledge(eq(fakeId), any(User.class)))
        .thenThrow(new ResourceNotFoundException("Alert not found: " + fakeId));

    mockMvc
        .perform(post("/api/v1/alerts/{alertId}/acknowledge", fakeId).with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Alert not found: " + fakeId));
  }
}
