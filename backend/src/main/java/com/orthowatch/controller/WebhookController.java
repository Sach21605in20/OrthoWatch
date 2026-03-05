package com.orthowatch.controller;

import com.orthowatch.config.WhatsAppProperties;
import com.orthowatch.dto.whatsapp.WhatsAppWebhookPayload;
import com.orthowatch.model.Episode;
import com.orthowatch.model.Patient;
import com.orthowatch.repository.EpisodeRepository;
import com.orthowatch.repository.PatientRepository;
import com.orthowatch.service.WhatsAppService;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhook/whatsapp")
@RequiredArgsConstructor
public class WebhookController {

  private final WhatsAppProperties whatsAppProperties;
  private final WhatsAppService whatsAppService;
  private final PatientRepository patientRepository;
  private final EpisodeRepository episodeRepository;

  @GetMapping
  public ResponseEntity<String> verifyWebhook(
      @RequestParam("hub.mode") String mode,
      @RequestParam("hub.verify_token") String token,
      @RequestParam("hub.challenge") String challenge) {

    if ("subscribe".equals(mode) && whatsAppProperties.getVerifyToken().equals(token)) {
      log.info("WhatsApp webhook verified successfully");
      return ResponseEntity.ok(challenge);
    }

    log.warn("WhatsApp webhook verification failed. Mode: {}, Token mismatch: {}", mode, true);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
  }

  @PostMapping
  public ResponseEntity<String> handleInboundMessage(
      @RequestBody WhatsAppWebhookPayload payload) {

    try {
      if (payload == null
          || payload.getEntry() == null
          || payload.getEntry().isEmpty()) {
        log.warn("Received empty webhook payload");
        return ResponseEntity.ok("EVENT_RECEIVED");
      }

      for (WhatsAppWebhookPayload.Entry entry : payload.getEntry()) {
        if (entry.getChanges() == null) continue;

        for (WhatsAppWebhookPayload.Change change : entry.getChanges()) {
          if (change.getValue() == null) continue;

          // Handle status updates (delivery receipts)
          if (change.getValue().getStatuses() != null) {
            for (WhatsAppWebhookPayload.Status status : change.getValue().getStatuses()) {
              log.debug(
                  "WhatsApp status update: {} -> {} for recipient {}",
                  status.getId(),
                  status.getStatus(),
                  status.getRecipientId());
            }
          }

          // Handle inbound messages
          if (change.getValue().getMessages() != null) {
            for (WhatsAppWebhookPayload.InboundMessage msg :
                change.getValue().getMessages()) {
              processInboundMessage(msg);
            }
          }
        }
      }
    } catch (Exception e) {
      log.error("Error processing webhook payload: {}", e.getMessage(), e);
    }

    // Always return 200 to prevent Meta from retrying
    return ResponseEntity.ok("EVENT_RECEIVED");
  }

  private void processInboundMessage(WhatsAppWebhookPayload.InboundMessage message) {
    String rawPhone = message.getFrom();
    String phoneNumber = normalizePhoneNumber(rawPhone);
    log.info(
        "Processing inbound message from {} (type: {})", phoneNumber, message.getType());

    // Lookup patient by phone number
    Optional<Patient> patientOpt =
        patientRepository.findByPhonePrimary(phoneNumber);

    if (patientOpt.isEmpty()) {
      log.warn("No patient found for phone number: {}", phoneNumber);
      return;
    }

    Patient patient = patientOpt.get();

    // Find active episode
    Optional<Episode> episodeOpt =
        episodeRepository.findByPatientIdAndStatus(patient.getId(), "ACTIVE");

    if (episodeOpt.isEmpty()) {
      log.warn("No active episode found for patient: {}", patient.getId());
      return;
    }

    Episode episode = episodeOpt.get();

    switch (message.getType()) {
      case "interactive":
        handleInteractiveReply(message, episode);
        break;
      case "image":
        handleImageMessage(message, episode);
        break;
      case "text":
        handleTextMessage(message, episode, phoneNumber);
        break;
      default:
        log.warn("Unsupported message type: {} from {}", message.getType(), phoneNumber);
        break;
    }
  }

  private void handleInteractiveReply(
      WhatsAppWebhookPayload.InboundMessage message, Episode episode) {

    if (message.getInteractive() == null || message.getInteractive().getButtonReply() == null) {
      log.warn("Interactive message missing button reply data");
      return;
    }

    String buttonId = message.getInteractive().getButtonReply().getId();
    String buttonTitle = message.getInteractive().getButtonReply().getTitle();

    log.info(
        "Button reply received: id={}, title={} for episode {}",
        buttonId,
        buttonTitle,
        episode.getId());

    // Handle consent responses
    if ("consent_yes".equals(buttonId)) {
      handleConsentResponse(episode, true);
      return;
    }
    if ("consent_no".equals(buttonId)) {
      handleConsentResponse(episode, false);
      return;
    }

    // Handle emergency follow-up
    if ("emergency_yes".equals(buttonId) || "emergency_no".equals(buttonId)) {
      log.info(
          "Emergency follow-up response: {} for episode {}",
          buttonId,
          episode.getId());
      return;
    }

    // Handle checklist responses (pain, swelling, etc.)
    log.info(
        "Checklist response: {} ({}) for episode {}",
        buttonId,
        buttonTitle,
        episode.getId());
  }

  private void handleConsentResponse(Episode episode, boolean consented) {
    if (consented) {
      episode.setConsentStatus("GRANTED");
      episode.setConsentTimestamp(OffsetDateTime.now());
      episodeRepository.save(episode);
      whatsAppService.sendWelcomeMessage(episode);
      log.info("Consent GRANTED for episode {}", episode.getId());
    } else {
      episode.setConsentStatus("DECLINED");
      episode.setConsentTimestamp(OffsetDateTime.now());
      episode.setStatus("CANCELLED");
      episodeRepository.save(episode);
      log.info("Consent DECLINED for episode {}. Episode cancelled.", episode.getId());
    }
  }

  private void handleImageMessage(
      WhatsAppWebhookPayload.InboundMessage message, Episode episode) {
    if (message.getImage() == null) {
      log.warn("Image message missing image data");
      return;
    }

    log.info(
        "Image received from patient for episode {}: mediaId={}",
        episode.getId(),
        message.getImage().getId());
  }

  private void handleTextMessage(
      WhatsAppWebhookPayload.InboundMessage message,
      Episode episode,
      String phoneNumber) {

    String text = message.getText() != null ? message.getText().getBody() : "";

    // Check for emergency keywords
    String lowerText = text.toLowerCase();
    if (lowerText.contains("breathless")
        || lowerText.contains("chest pain")
        || lowerText.contains("cannot breathe")
        || lowerText.contains("dvt")) {
      log.warn("Emergency keyword detected from {} for episode {}", phoneNumber, episode.getId());
      whatsAppService.sendEmergencyOverride(episode);
      whatsAppService.sendEmergencyFollowUp(episode);
      return;
    }

    // For non-emergency free text, ask to use buttons
    whatsAppService.sendUseButtonsReply(phoneNumber);
  }

  private String normalizePhoneNumber(String phoneNumber) {
    // Meta sends phone numbers without '+' prefix
    // Our DB stores with '+' prefix (e.g., "+919876543210")
    if (phoneNumber != null && !phoneNumber.startsWith("+")) {
      return "+" + phoneNumber;
    }
    return phoneNumber;
  }
}
