package com.orthowatch.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.orthowatch.dto.ChecklistResponseDto;
import com.orthowatch.model.*;
import com.orthowatch.repository.AlertRepository;
import com.orthowatch.repository.DailyResponseRepository;
import com.orthowatch.repository.EpisodeRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChecklistServiceTest {

  @Mock private EpisodeRepository episodeRepository;
  @Mock private DailyResponseRepository dailyResponseRepository;
  @Mock private AlertRepository alertRepository;
  @Mock private RiskEngineService riskEngineService;

  @InjectMocks private ChecklistService checklistService;

  private Episode episode;
  private UUID episodeId;

  @BeforeEach
  void setUp() {
    episodeId = UUID.randomUUID();

    User surgeon =
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

    // Template with day-3 config requiring: pain_score, swelling_level, fever_check
    Map<String, Object> dayConfig =
        Map.of("questions", List.of("pain_score", "swelling_level", "fever_check"));
    Map<String, Object> daysConfig = Map.of("3", dayConfig);
    Map<String, Object> checklistConfig = Map.of("days", daysConfig);

    RecoveryTemplate template =
        RecoveryTemplate.builder()
            .id(UUID.randomUUID())
            .surgeryType("TKR")
            .displayName("Total Knee Replacement")
            .checklistConfig(checklistConfig)
            .milestoneConfig(Map.of())
            .monitoringDays(14)
            .createdBy(surgeon)
            .build();

    episode =
        Episode.builder()
            .id(episodeId)
            .patient(patient)
            .primarySurgeon(surgeon)
            .template(template)
            .surgeryDate(LocalDate.of(2026, 1, 1))
            .dischargeDate(LocalDate.of(2026, 1, 3))
            .currentDay(3)
            .status("ACTIVE")
            .painScoreDischarge(6)
            .swellingLevelDischarge("MODERATE")
            .build();
  }

  @Test
  void shouldMarkCompleteWhenAllFieldsPresent() {
    ChecklistResponseDto dto =
        ChecklistResponseDto.builder()
            .painScore(5)
            .swellingLevel("MILD")
            .feverLevel("NO_FEVER")
            .responderType("PATIENT")
            .build();

    when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    when(dailyResponseRepository.findByEpisodeIdAndDayNumber(episodeId, 3))
        .thenReturn(Optional.empty());
    when(dailyResponseRepository.save(any(DailyResponse.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(riskEngineService.calculateRiskScore(any(), any())).thenReturn(null);

    DailyResponse result = checklistService.processResponse(episodeId, 3, dto);

    assertThat(result.getCompletionStatus()).isEqualTo("COMPLETED");
    assertThat(result.getResponseCompletedAt()).isNotNull();
    verify(riskEngineService).calculateRiskScore(eq(episode), any(DailyResponse.class));
  }

  @Test
  void shouldMarkPartialWhenSomeFieldsMissing() {
    // Only provide pain_score — swelling and fever missing
    ChecklistResponseDto dto =
        ChecklistResponseDto.builder().painScore(5).responderType("PATIENT").build();

    when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    when(dailyResponseRepository.findByEpisodeIdAndDayNumber(episodeId, 3))
        .thenReturn(Optional.empty());
    when(dailyResponseRepository.save(any(DailyResponse.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    DailyResponse result = checklistService.processResponse(episodeId, 3, dto);

    assertThat(result.getCompletionStatus()).isEqualTo("PARTIAL");
    verify(riskEngineService, never()).calculateRiskScore(any(), any());
  }

  @Test
  void shouldMarkPendingForEmptyResponse() {
    ChecklistResponseDto dto = ChecklistResponseDto.builder().responderType("PATIENT").build();

    when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    when(dailyResponseRepository.findByEpisodeIdAndDayNumber(episodeId, 3))
        .thenReturn(Optional.empty());
    when(dailyResponseRepository.save(any(DailyResponse.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    DailyResponse result = checklistService.processResponse(episodeId, 3, dto);

    assertThat(result.getCompletionStatus()).isEqualTo("PENDING");
    verify(riskEngineService, never()).calculateRiskScore(any(), any());
  }

  @Test
  void shouldTriggerRiskEngineOnCompletion() {
    ChecklistResponseDto dto =
        ChecklistResponseDto.builder()
            .painScore(7)
            .swellingLevel("MODERATE")
            .feverLevel("100_TO_102")
            .responderType("PATIENT")
            .build();

    when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    when(dailyResponseRepository.findByEpisodeIdAndDayNumber(episodeId, 3))
        .thenReturn(Optional.empty());
    when(dailyResponseRepository.save(any(DailyResponse.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(riskEngineService.calculateRiskScore(any(), any())).thenReturn(null);

    checklistService.processResponse(episodeId, 3, dto);

    verify(riskEngineService, times(1)).calculateRiskScore(eq(episode), any(DailyResponse.class));
  }

  @Test
  void shouldCancelAlertOnLateResponse() {
    Alert pendingAlert =
        Alert.builder()
            .id(UUID.randomUUID())
            .episode(episode)
            .alertType("NON_RESPONSE")
            .severity("MEDIUM")
            .assignedTo(episode.getPrimarySurgeon())
            .status("PENDING")
            .build();

    when(alertRepository.findByEpisodeId(episodeId)).thenReturn(List.of(pendingAlert));
    when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

    checklistService.handleLateResponse(episodeId, 3);

    assertThat(pendingAlert.getStatus()).isEqualTo("CANCELLED");
    verify(alertRepository).save(pendingAlert);
  }

  @Test
  void shouldCreateNewResponseIfNotExists() {
    ChecklistResponseDto dto =
        ChecklistResponseDto.builder()
            .painScore(4)
            .swellingLevel("NONE")
            .feverLevel("NO_FEVER")
            .responderType("CAREGIVER")
            .build();

    when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    when(dailyResponseRepository.findByEpisodeIdAndDayNumber(episodeId, 3))
        .thenReturn(Optional.empty());
    when(dailyResponseRepository.save(any(DailyResponse.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(riskEngineService.calculateRiskScore(any(), any())).thenReturn(null);

    DailyResponse result = checklistService.processResponse(episodeId, 3, dto);

    assertThat(result.getEpisode()).isEqualTo(episode);
    assertThat(result.getDayNumber()).isEqualTo(3);
    assertThat(result.getResponderType()).isEqualTo("CAREGIVER");
  }
}
