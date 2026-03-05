package com.orthowatch.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthowatch.config.WhatsAppProperties;
import com.orthowatch.dto.whatsapp.WhatsAppWebhookPayload;
import com.orthowatch.model.Episode;
import com.orthowatch.model.Patient;
import com.orthowatch.repository.EpisodeRepository;
import com.orthowatch.repository.PatientRepository;
import com.orthowatch.service.WhatsAppService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
@ContextConfiguration(classes = {WebhookController.class})
@AutoConfigureMockMvc(addFilters = false)
class WebhookControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private WhatsAppProperties whatsAppProperties;
  @MockBean private WhatsAppService whatsAppService;
  @MockBean private PatientRepository patientRepository;
  @MockBean private EpisodeRepository episodeRepository;

  @BeforeEach
  void setUp() {
    when(whatsAppProperties.getVerifyToken()).thenReturn("test-verify-token");
  }

  @Test
  void verifyWebhook_withValidToken_shouldReturnChallenge() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/webhook/whatsapp")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "test-verify-token")
                .param("hub.challenge", "challenge-12345"))
        .andExpect(status().isOk())
        .andExpect(content().string("challenge-12345"));
  }

  @Test
  void verifyWebhook_withInvalidToken_shouldReturn403() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/webhook/whatsapp")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "wrong-token")
                .param("hub.challenge", "challenge-12345"))
        .andExpect(status().isForbidden());
  }

  @Test
  void handleInboundMessage_buttonReply_shouldReturn200() throws Exception {
    Patient patient =
        Patient.builder()
            .id(UUID.randomUUID())
            .phonePrimary("+919876543210")
            .fullName("Test Patient")
            .age(55)
            .build();

    Episode episode =
        Episode.builder()
            .id(UUID.randomUUID())
            .patient(patient)
            .status("ACTIVE")
            .consentStatus("GRANTED")
            .build();

    when(patientRepository.findByPhonePrimary("+919876543210"))
        .thenReturn(Optional.of(patient));
    when(episodeRepository.findByPatientIdAndStatus(patient.getId(), "ACTIVE"))
        .thenReturn(Optional.of(episode));

    WhatsAppWebhookPayload payload = buildInteractivePayload("919876543210", "pain_moderate", "4-6 Moderate");

    mockMvc
        .perform(
            post("/api/v1/webhook/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isOk())
        .andExpect(content().string("EVENT_RECEIVED"));
  }

  @Test
  void handleInboundMessage_consentYes_shouldReturn200AndUpdateEpisode() throws Exception {
    Patient patient =
        Patient.builder()
            .id(UUID.randomUUID())
            .phonePrimary("+919876543210")
            .fullName("Test Patient")
            .age(55)
            .build();

    Episode episode =
        Episode.builder()
            .id(UUID.randomUUID())
            .patient(patient)
            .status("ACTIVE")
            .consentStatus("PENDING")
            .build();

    when(patientRepository.findByPhonePrimary("+919876543210"))
        .thenReturn(Optional.of(patient));
    when(episodeRepository.findByPatientIdAndStatus(patient.getId(), "ACTIVE"))
        .thenReturn(Optional.of(episode));

    WhatsAppWebhookPayload payload = buildInteractivePayload("919876543210", "consent_yes", "YES");

    mockMvc
        .perform(
            post("/api/v1/webhook/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isOk())
        .andExpect(content().string("EVENT_RECEIVED"));

    verify(episodeRepository).save(any(Episode.class));
    verify(whatsAppService).sendWelcomeMessage(any(Episode.class));
  }

  @Test
  void handleInboundMessage_freeText_shouldReturn200AndSendUseButtonsReply() throws Exception {
    Patient patient =
        Patient.builder()
            .id(UUID.randomUUID())
            .phonePrimary("+919876543210")
            .fullName("Test Patient")
            .age(55)
            .build();

    Episode episode =
        Episode.builder()
            .id(UUID.randomUUID())
            .patient(patient)
            .status("ACTIVE")
            .consentStatus("GRANTED")
            .build();

    when(patientRepository.findByPhonePrimary("+919876543210"))
        .thenReturn(Optional.of(patient));
    when(episodeRepository.findByPatientIdAndStatus(patient.getId(), "ACTIVE"))
        .thenReturn(Optional.of(episode));

    WhatsAppWebhookPayload payload = buildTextPayload("919876543210", "hello how are you");

    mockMvc
        .perform(
            post("/api/v1/webhook/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isOk())
        .andExpect(content().string("EVENT_RECEIVED"));

    verify(whatsAppService).sendUseButtonsReply("+919876543210");
  }

  // Helper methods to build webhook payloads

  private WhatsAppWebhookPayload buildInteractivePayload(
      String from, String buttonId, String buttonTitle) {
    return WhatsAppWebhookPayload.builder()
        .object("whatsapp_business_account")
        .entry(
            List.of(
                WhatsAppWebhookPayload.Entry.builder()
                    .id("entry-1")
                    .changes(
                        List.of(
                            WhatsAppWebhookPayload.Change.builder()
                                .field("messages")
                                .value(
                                    WhatsAppWebhookPayload.Value.builder()
                                        .messagingProduct("whatsapp")
                                        .messages(
                                            List.of(
                                                WhatsAppWebhookPayload.InboundMessage
                                                    .builder()
                                                    .from(from)
                                                    .id("msg-123")
                                                    .timestamp("1234567890")
                                                    .type("interactive")
                                                    .interactive(
                                                        WhatsAppWebhookPayload
                                                            .InteractiveContent
                                                            .builder()
                                                            .type("button_reply")
                                                            .buttonReply(
                                                                WhatsAppWebhookPayload
                                                                    .ButtonReply
                                                                    .builder()
                                                                    .id(buttonId)
                                                                    .title(buttonTitle)
                                                                    .build())
                                                            .build())
                                                    .build()))
                                        .build())
                                .build()))
                    .build()))
        .build();
  }

  private WhatsAppWebhookPayload buildTextPayload(String from, String text) {
    return WhatsAppWebhookPayload.builder()
        .object("whatsapp_business_account")
        .entry(
            List.of(
                WhatsAppWebhookPayload.Entry.builder()
                    .id("entry-1")
                    .changes(
                        List.of(
                            WhatsAppWebhookPayload.Change.builder()
                                .field("messages")
                                .value(
                                    WhatsAppWebhookPayload.Value.builder()
                                        .messagingProduct("whatsapp")
                                        .messages(
                                            List.of(
                                                WhatsAppWebhookPayload.InboundMessage
                                                    .builder()
                                                    .from(from)
                                                    .id("msg-456")
                                                    .timestamp("1234567890")
                                                    .type("text")
                                                    .text(
                                                        WhatsAppWebhookPayload
                                                            .TextContent
                                                            .builder()
                                                            .body(text)
                                                            .build())
                                                    .build()))
                                        .build())
                                .build()))
                    .build()))
        .build();
  }
}
