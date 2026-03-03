package com.orthowatch.scheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.orthowatch.model.*;
import com.orthowatch.repository.AlertRepository;
import com.orthowatch.repository.DailyResponseRepository;
import com.orthowatch.repository.EpisodeRepository;
import com.orthowatch.repository.SessionRepository;
import com.orthowatch.service.AlertService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduledTasksTest {

  @Mock private EpisodeRepository episodeRepository;
  @Mock private DailyResponseRepository dailyResponseRepository;
  @Mock private AlertRepository alertRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private AlertService alertService;

  @InjectMocks private ScheduledTasks scheduledTasks;

  private Episode activeEpisode;
  private User surgeon;

  @BeforeEach
  void setUp() {
    surgeon =
        User.builder()
            .id(UUID.randomUUID())
            .email("surgeon@orthowatch.com")
            .fullName("Dr. Surgeon")
            .role("SURGEON")
            .build();

    Patient patient =
        Patient.builder()
            .id(UUID.randomUUID())
            .fullName("Test Patient")
            .phonePrimary("+919876543210")
            .age(55)
            .build();

    RecoveryTemplate template =
        RecoveryTemplate.builder()
            .id(UUID.randomUUID())
            .surgeryType("TKR")
            .displayName("Total Knee Replacement")
            .checklistConfig(Map.of())
            .milestoneConfig(Map.of())
            .monitoringDays(14)
            .createdBy(surgeon)
            .build();

    activeEpisode =
        Episode.builder()
            .id(UUID.randomUUID())
            .patient(patient)
            .primarySurgeon(surgeon)
            .template(template)
            .surgeryDate(LocalDate.of(2026, 1, 1))
            .dischargeDate(LocalDate.of(2026, 1, 3))
            .currentDay(2)
            .status("ACTIVE")
            .consentStatus("GRANTED")
            .painScoreDischarge(6)
            .swellingLevelDischarge("MODERATE")
            .build();
  }

  @Test
  void shouldDispatchChecklistsForActiveConsentedEpisodes() {
    when(episodeRepository.findByStatusAndConsentStatus("ACTIVE", "GRANTED"))
        .thenReturn(List.of(activeEpisode));
    when(episodeRepository.save(any(Episode.class))).thenAnswer(inv -> inv.getArgument(0));
    when(dailyResponseRepository.save(any(DailyResponse.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    scheduledTasks.dispatchDailyChecklists();

    assertThat(activeEpisode.getCurrentDay()).isEqualTo(3);

    ArgumentCaptor<DailyResponse> responseCaptor = ArgumentCaptor.forClass(DailyResponse.class);
    verify(dailyResponseRepository).save(responseCaptor.capture());

    DailyResponse saved = responseCaptor.getValue();
    assertThat(saved.getDayNumber()).isEqualTo(3);
    assertThat(saved.getCompletionStatus()).isEqualTo("PENDING");
    assertThat(saved.getEpisode()).isEqualTo(activeEpisode);
  }

  @Test
  void shouldSkipEpisodesWithoutConsent() {
    when(episodeRepository.findByStatusAndConsentStatus("ACTIVE", "GRANTED"))
        .thenReturn(Collections.emptyList());

    scheduledTasks.dispatchDailyChecklists();

    verify(dailyResponseRepository, never()).save(any());
    verify(episodeRepository, never()).save(any());
  }

  @Test
  void shouldCreateNonResponseAlertAfter8Hours() {
    DailyResponse staleResponse =
        DailyResponse.builder()
            .id(UUID.randomUUID())
            .episode(activeEpisode)
            .dayNumber(2)
            .responderType("PATIENT")
            .completionStatus("PENDING")
            .createdAt(OffsetDateTime.now().minusHours(9))
            .build();

    when(dailyResponseRepository.findByCompletionStatusAndCreatedAtBefore(
            eq("PENDING"), any(OffsetDateTime.class)))
        .thenReturn(List.of(staleResponse));
    when(alertRepository.findByEpisodeId(activeEpisode.getId()))
        .thenReturn(Collections.emptyList());
    when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

    scheduledTasks.escalateNonResponses();

    ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
    verify(alertRepository).save(alertCaptor.capture());

    Alert alert = alertCaptor.getValue();
    assertThat(alert.getAlertType()).isEqualTo("NON_RESPONSE");
    assertThat(alert.getSeverity()).isEqualTo("MEDIUM");
    assertThat(alert.getAssignedTo()).isEqualTo(surgeon);
    assertThat(alert.getStatus()).isEqualTo("PENDING");
  }

  @Test
  void shouldCreateConsentTimeoutAlertAfter24Hours() {
    Episode pendingConsentEpisode =
        Episode.builder()
            .id(UUID.randomUUID())
            .patient(activeEpisode.getPatient())
            .primarySurgeon(surgeon)
            .template(activeEpisode.getTemplate())
            .surgeryDate(LocalDate.of(2026, 1, 1))
            .dischargeDate(LocalDate.of(2026, 1, 3))
            .currentDay(0)
            .status("ACTIVE")
            .consentStatus("PENDING")
            .painScoreDischarge(6)
            .swellingLevelDischarge("MODERATE")
            .createdAt(OffsetDateTime.now().minusHours(25))
            .build();

    when(episodeRepository.findByConsentStatusAndCreatedAtBefore(
            eq("PENDING"), any(OffsetDateTime.class)))
        .thenReturn(List.of(pendingConsentEpisode));
    when(alertRepository.findByEpisodeId(pendingConsentEpisode.getId()))
        .thenReturn(Collections.emptyList());
    when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

    scheduledTasks.checkConsentTimeouts();

    ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
    verify(alertRepository).save(alertCaptor.capture());

    Alert alert = alertCaptor.getValue();
    assertThat(alert.getAlertType()).isEqualTo("CONSENT_TIMEOUT");
    assertThat(alert.getSeverity()).isEqualTo("HIGH");
    assertThat(alert.getAssignedTo()).isEqualTo(surgeon);
  }

  @Test
  void shouldCleanupExpiredSessions() {
    scheduledTasks.cleanupExpiredSessions();

    verify(sessionRepository).deleteByExpiresAtBefore(any(OffsetDateTime.class));
  }
}
