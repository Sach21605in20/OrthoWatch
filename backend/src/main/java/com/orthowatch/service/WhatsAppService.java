package com.orthowatch.service;

import com.orthowatch.model.Episode;
import com.orthowatch.model.Patient;
import com.orthowatch.repository.PatientRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

  private final WhatsAppApiClient apiClient;
  private final PatientRepository patientRepository;

  public void sendConsentRequest(Episode episode) {
    Patient patient = getPatient(episode);
    String phone = patient.getPhonePrimary();
    String lang = patient.getPreferredLanguage();

    String consentMessage =
        "Welcome to OrthoWatch Recovery Monitoring.\n"
            + "Before we begin, please confirm:\n"
            + "☐ I agree to participate in digital recovery monitoring\n"
            + "☐ I consent to sharing medical images for clinical review\n"
            + "☐ I consent to secure data storage of my health information\n\n"
            + "Reply YES to confirm, or call the hospital for questions.";

    apiClient.sendInteractiveButtonMessage(
        phone,
        consentMessage,
        List.of(
            WhatsAppApiClient.ButtonOption.builder().id("consent_yes").title("YES").build(),
            WhatsAppApiClient.ButtonOption.builder().id("consent_no").title("NO").build()));

    log.info(
        "Consent request sent to patient {} for episode {}", patient.getId(), episode.getId());
  }

  public void sendWelcomeMessage(Episode episode) {
    Patient patient = getPatient(episode);
    String phone = patient.getPhonePrimary();

    String surgeryType =
        episode.getTemplate() != null ? episode.getTemplate().getDisplayName() : "your surgery";

    String welcomeMessage =
        "✅ Thank you for consenting to OrthoWatch monitoring!\n\n"
            + "Surgery: "
            + surgeryType
            + "\n"
            + "You will receive a daily recovery checklist at 9:00 AM for the next "
            + (episode.getTemplate() != null ? episode.getTemplate().getMonitoringDays() : 14)
            + " days.\n\n"
            + "Please respond to each question using the buttons provided.\n"
            + "On Day 3 and Day 5, you will be asked to upload a wound photo.\n\n"
            + "If you experience breathlessness or chest pain at any time, "
            + "go to the nearest Emergency Department IMMEDIATELY.";

    apiClient.sendTextMessage(phone, welcomeMessage);

    log.info(
        "Welcome message sent to patient {} for episode {}", patient.getId(), episode.getId());
  }

  public void sendDailyChecklist(Episode episode, int dayNumber) {
    Patient patient = getPatient(episode);
    String phone = patient.getPhonePrimary();

    String intro = "📋 Day " + dayNumber + " Post-Surgery Checklist\n\nHow is your pain today?";

    apiClient.sendInteractiveButtonMessage(
        phone,
        intro,
        List.of(
            WhatsAppApiClient.ButtonOption.builder().id("pain_low").title("0-3 Low").build(),
            WhatsAppApiClient.ButtonOption.builder()
                .id("pain_moderate")
                .title("4-6 Moderate")
                .build(),
            WhatsAppApiClient.ButtonOption.builder()
                .id("pain_severe")
                .title("7-10 Severe")
                .build()));

    log.info(
        "Daily checklist (Day {}) sent to patient {} for episode {}",
        dayNumber,
        patient.getId(),
        episode.getId());
  }

  public void sendWoundImageRequest(Episode episode, int dayNumber) {
    Patient patient = getPatient(episode);
    String phone = patient.getPhonePrimary();

    String imagePrompt =
        "📸 Day "
            + dayNumber
            + " — Wound Image Required\n\n"
            + "Please upload a clear photo of your surgical wound.\n"
            + "Tips:\n"
            + "• Use good lighting\n"
            + "• Hold camera close to wound area\n"
            + "• Make sure image is not blurry";

    apiClient.sendTextMessage(phone, imagePrompt);

    log.info(
        "Wound image request (Day {}) sent to patient {} for episode {}",
        dayNumber,
        patient.getId(),
        episode.getId());
  }

  public void sendReminder(Episode episode) {
    Patient patient = getPatient(episode);
    String phone = patient.getPhonePrimary();

    String reminderMessage =
        "⏰ Reminder: We haven't heard from you today.\n\n"
            + "Please complete your recovery checklist — it only takes 2 minutes.\n"
            + "Your responses help your doctor monitor your recovery.";

    apiClient.sendTextMessage(phone, reminderMessage);

    log.info("Reminder sent to patient {} for episode {}", patient.getId(), episode.getId());
  }

  public void sendEmergencyOverride(Episode episode) {
    Patient patient = getPatient(episode);
    String phone = patient.getPhonePrimary();

    String emergencyMessage =
        "⚠️ URGENT: Breathlessness after surgery may indicate a serious condition.\n\n"
            + "👉 Please go to the nearest Emergency Department IMMEDIATELY.\n"
            + "👉 Call 112 (National Emergency)\n\n"
            + "Do NOT wait. Your safety is our priority.";

    apiClient.sendTextMessage(phone, emergencyMessage);

    log.info(
        "Emergency override sent to patient {} for episode {}", patient.getId(), episode.getId());
  }

  public void sendEmergencyFollowUp(Episode episode) {
    Patient patient = getPatient(episode);
    String phone = patient.getPhonePrimary();

    apiClient.sendInteractiveButtonMessage(
        phone,
        "Are you going to the hospital now?",
        List.of(
            WhatsAppApiClient.ButtonOption.builder()
                .id("emergency_yes")
                .title("YES")
                .build(),
            WhatsAppApiClient.ButtonOption.builder()
                .id("emergency_no")
                .title("NO")
                .build()));

    log.info(
        "Emergency follow-up sent to patient {} for episode {}",
        patient.getId(),
        episode.getId());
  }

  public void sendUseButtonsReply(String phoneNumber) {
    apiClient.sendTextMessage(
        phoneNumber,
        "Please use the buttons provided to respond. Free-text messages are not supported.");
    log.info("Use-buttons reply sent to {}", phoneNumber);
  }

  private Patient getPatient(Episode episode) {
    return patientRepository
        .findById(episode.getPatient().getId())
        .orElseThrow(
            () ->
                new com.orthowatch.exception.ResourceNotFoundException(
                    "Patient not found for episode " + episode.getId()));
  }
}
