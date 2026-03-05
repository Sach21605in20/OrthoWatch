package com.orthowatch.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.orthowatch.model.Episode;
import com.orthowatch.model.Patient;
import com.orthowatch.model.RecoveryTemplate;
import com.orthowatch.repository.PatientRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhatsAppServiceTest {

  @Mock private WhatsAppApiClient apiClient;
  @Mock private PatientRepository patientRepository;

  @InjectMocks private WhatsAppService whatsAppService;

  private Patient testPatient;
  private Episode testEpisode;

  @BeforeEach
  void setUp() {
    testPatient =
        Patient.builder()
            .id(UUID.randomUUID())
            .fullName("Test Patient")
            .phonePrimary("+919876543210")
            .preferredLanguage("en")
            .age(55)
            .build();

    RecoveryTemplate template =
        RecoveryTemplate.builder()
            .id(UUID.randomUUID())
            .surgeryType("TKR")
            .displayName("Total Knee Replacement")
            .monitoringDays(14)
            .build();

    testEpisode =
        Episode.builder()
            .id(UUID.randomUUID())
            .patient(testPatient)
            .template(template)
            .status("ACTIVE")
            .consentStatus("PENDING")
            .build();

    lenient()
        .when(patientRepository.findById(testPatient.getId()))
        .thenReturn(Optional.of(testPatient));
  }

  @Test
  void sendConsentRequest_shouldSendInteractiveButtonMessage() {
    whatsAppService.sendConsentRequest(testEpisode);

    verify(apiClient)
        .sendInteractiveButtonMessage(
            eq("+919876543210"),
            contains("consent"),
            argThat(
                buttons ->
                    buttons.size() == 2
                        && buttons.get(0).getId().equals("consent_yes")
                        && buttons.get(1).getId().equals("consent_no")));
  }

  @Test
  void sendDailyChecklist_shouldSendPainQuestionWithButtons() {
    whatsAppService.sendDailyChecklist(testEpisode, 3);

    verify(apiClient)
        .sendInteractiveButtonMessage(
            eq("+919876543210"),
            contains("Day 3"),
            argThat(
                buttons ->
                    buttons.size() == 3
                        && buttons.get(0).getId().equals("pain_low")
                        && buttons.get(1).getId().equals("pain_moderate")
                        && buttons.get(2).getId().equals("pain_severe")));
  }

  @Test
  void sendWoundImageRequest_shouldSendTextMessageWithImagePrompt() {
    whatsAppService.sendWoundImageRequest(testEpisode, 3);

    verify(apiClient)
        .sendTextMessage(
            eq("+919876543210"),
            argThat(text -> text.contains("Day 3") && text.contains("Wound Image")));
  }

  @Test
  void sendReminder_shouldSendReminderText() {
    whatsAppService.sendReminder(testEpisode);

    verify(apiClient)
        .sendTextMessage(
            eq("+919876543210"),
            argThat(text -> text.contains("Reminder") && text.contains("checklist")));
  }

  @Test
  void sendEmergencyOverride_shouldSendUrgentMessage() {
    whatsAppService.sendEmergencyOverride(testEpisode);

    verify(apiClient)
        .sendTextMessage(
            eq("+919876543210"),
            argThat(
                text ->
                    text.contains("URGENT")
                        && text.contains("Emergency Department")
                        && text.contains("112")));
  }

  @Test
  void sendEmergencyFollowUp_shouldSendConfirmationButtons() {
    whatsAppService.sendEmergencyFollowUp(testEpisode);

    verify(apiClient)
        .sendInteractiveButtonMessage(
            eq("+919876543210"),
            contains("hospital"),
            argThat(
                buttons ->
                    buttons.size() == 2
                        && buttons.get(0).getId().equals("emergency_yes")
                        && buttons.get(1).getId().equals("emergency_no")));
  }
}
