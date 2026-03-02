package com.orthowatch.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.orthowatch.model.*;
import com.orthowatch.repository.*;
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
class RiskEngineServiceTest {

  @Mock private RiskRuleRepository riskRuleRepository;
  @Mock private RiskScoreRepository riskScoreRepository;
  @Mock private AlertRepository alertRepository;
  @Mock private DailyResponseRepository dailyResponseRepository;

  @InjectMocks private RiskEngineService riskEngineService;

  private Episode episode;
  private User surgeon;
  private List<RiskRule> activeRules;

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

    episode =
        Episode.builder()
            .id(UUID.randomUUID())
            .patient(patient)
            .primarySurgeon(surgeon)
            .surgeryDate(LocalDate.of(2026, 1, 1))
            .dischargeDate(LocalDate.of(2026, 1, 3))
            .currentDay(3)
            .status("ACTIVE")
            .painScoreDischarge(6)
            .swellingLevelDischarge("MODERATE")
            .build();

    // Build the 5 default active rules
    activeRules = new ArrayList<>();
    activeRules.add(buildRule("FEVER_HIGH", "FEVER_ABOVE_100", "HIGH", 30));
    activeRules.add(buildRule("DVT_SYMPTOMS", "DVT_ANY_PRESENT", "HIGH", 30));
    activeRules.add(buildRule("PAIN_SPIKE", "PAIN_SPIKE_GT_2", "MEDIUM", 15));
    activeRules.add(buildRule("SWELLING_TREND", "SWELLING_INCREASING_2D", "MEDIUM", 15));
    activeRules.add(buildRule("WOUND_CONCERN", "WOUND_REDNESS_DISCHARGE", "HIGH", 25));
  }

  // ──────────────────────── Individual Rule Tests ────────────────────────

  @Test
  void shouldScoreHighForFeverAbove100() {
    DailyResponse response = buildResponse(3, 4, "MODERATE", "100_TO_102", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(response));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, response);

    assertThat(result.getCompositeScore()).isGreaterThanOrEqualTo(30);
    Map<String, Object> factors = result.getContributingFactors();
    assertThat(factors).containsKey("FEVER_HIGH");
  }

  @Test
  void shouldScoreMediumForPainSpike() {
    // Yesterday pain was 3, today pain is 7 → delta = 4 > 2
    DailyResponse yesterday = buildResponse(2, 3, "MILD", "NO_FEVER", null);
    DailyResponse today = buildResponse(3, 7, "MILD", "NO_FEVER", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(today, yesterday));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, today);

    assertThat(result.getCompositeScore()).isGreaterThanOrEqualTo(15);
    assertThat(result.getContributingFactors()).containsKey("PAIN_SPIKE");
  }

  @Test
  void shouldScoreMediumForSwellingTrend() {
    // 2-day swelling increase: NONE → MILD → MODERATE
    DailyResponse day1 = buildResponse(1, 3, "NONE", "NO_FEVER", null);
    DailyResponse day2 = buildResponse(2, 3, "MILD", "NO_FEVER", null);
    DailyResponse day3 = buildResponse(3, 3, "MODERATE", "NO_FEVER", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(day3, day2, day1));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, day3);

    assertThat(result.getCompositeScore()).isGreaterThanOrEqualTo(15);
    assertThat(result.getContributingFactors()).containsKey("SWELLING_TREND");
  }

  @Test
  void shouldScoreHighForDvtSymptoms() {
    DailyResponse response =
        buildResponse(3, 4, "MILD", "NO_FEVER", new String[] {"CALF_PAIN", "LEG_SWELLING"});

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(response));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, response);

    assertThat(result.getCompositeScore()).isGreaterThanOrEqualTo(30);
    assertThat(result.getContributingFactors()).containsKey("DVT_SYMPTOMS");
  }

  // ──────────────────────── Composite Score Tests ────────────────────────

  @Test
  void shouldCalculateCompositeScoreCorrectly() {
    // Fever (30) + DVT (30) = 60
    DailyResponse response =
        buildResponse(3, 4, "MILD", "ABOVE_102", new String[] {"CALF_PAIN"});

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(response));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, response);

    assertThat(result.getCompositeScore()).isEqualTo(60);
  }

  @Test
  void shouldCapCompositeScoreAt100() {
    // Fever (30) + DVT (30) + Pain spike (15) + Swelling (15) + Wound (25) = 115 → capped at 100
    // But WOUND_CONCERN is always false for now, so let's trigger 4 rules = 90
    // Actually let's make a scenario with all possible: fever + DVT + pain spike
    // Fever (30) + DVT (30) + pain spike (15) = 75 which is under 100

    // To test cap, let's add extra weight by manipulating rules
    List<RiskRule> heavyRules = new ArrayList<>();
    heavyRules.add(buildRule("FEVER_HIGH", "FEVER_ABOVE_100", "HIGH", 60));
    heavyRules.add(buildRule("DVT_SYMPTOMS", "DVT_ANY_PRESENT", "HIGH", 60));

    DailyResponse response =
        buildResponse(3, 4, "MILD", "ABOVE_102", new String[] {"CALF_PAIN"});

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(heavyRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(response));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, response);

    assertThat(result.getCompositeScore()).isEqualTo(100);
  }

  // ──────────────────────── Risk Level Classification ────────────────────────

  @Test
  void shouldClassifyRiskLevelCorrectly() {
    // LOW: 0-30 — trigger no rules → 0 = LOW
    DailyResponse lowResponse = buildResponse(3, 3, "NONE", "NO_FEVER", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(lowResponse));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore lowResult = riskEngineService.calculateRiskScore(episode, lowResponse);
    assertThat(lowResult.getRiskLevel()).isEqualTo("LOW");
    assertThat(lowResult.getCompositeScore()).isLessThanOrEqualTo(30);
  }

  // ──────────────────────── Trajectory Tests ────────────────────────

  @Test
  void shouldComputeTrajectoryFromThreeDayTrend() {
    // Day 1: score 60, Day 2: score 40, Day 3 (today): score 15 → IMPROVING
    RiskScore day1Score =
        RiskScore.builder().dayNumber(1).compositeScore(60).riskLevel("HIGH").build();
    RiskScore day2Score =
        RiskScore.builder().dayNumber(2).compositeScore(40).riskLevel("MEDIUM").build();

    DailyResponse today = buildResponse(3, 3, "NONE", "NO_FEVER", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(today));
    when(riskScoreRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(day2Score, day1Score));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, today);

    assertThat(result.getTrajectory()).isEqualTo("IMPROVING");
  }

  @Test
  void shouldReturnNullTrajectoryForDay1() {
    episode.setCurrentDay(1);
    DailyResponse day1Response = buildResponse(1, 5, "MILD", "NO_FEVER", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(day1Response));
    when(riskScoreRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(Collections.emptyList());
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, day1Response);

    assertThat(result.getTrajectory()).isNull();
  }

  // ──────────────────────── Rule Version Snapshot ────────────────────────

  @Test
  void shouldStoreRuleVersionSnapshot() {
    DailyResponse response = buildResponse(3, 4, "MILD", "NO_FEVER", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(response));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, response);

    assertThat(result.getRuleVersionId()).isNotBlank();
    assertThat(result.getRuleSetSnapshot()).isNotNull();
    assertThat(result.getRuleSetSnapshot()).isNotEmpty();
    // Verify the snapshot contains our rule names
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules =
        (List<Map<String, Object>>) result.getRuleSetSnapshot().get("rules");
    assertThat(rules).hasSize(5);
  }

  // ──────────────────────── Alert Generation ────────────────────────

  @Test
  void shouldCreateAlertForHighRisk() {
    // Fever (30) + DVT (30) = 60 → MEDIUM (31-60), not quite HIGH
    // Let's trigger fever (30) + DVT (30) + pain spike (15) = 75 → HIGH
    DailyResponse yesterday = buildResponse(2, 3, "MILD", "NO_FEVER", null);
    DailyResponse today =
        buildResponse(3, 7, "MILD", "ABOVE_102", new String[] {"CALF_PAIN"});

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(today, yesterday));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(alertRepository.save(any(Alert.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, today);

    assertThat(result.getRiskLevel()).isEqualTo("HIGH");
    assertThat(result.getCompositeScore()).isGreaterThanOrEqualTo(61);

    ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
    verify(alertRepository).save(alertCaptor.capture());

    Alert alert = alertCaptor.getValue();
    assertThat(alert.getAlertType()).isEqualTo("HIGH_RISK");
    assertThat(alert.getSeverity()).isEqualTo("HIGH");
    assertThat(alert.getAssignedTo()).isEqualTo(surgeon);
    assertThat(alert.getEpisode()).isEqualTo(episode);
    assertThat(alert.getStatus()).isEqualTo("PENDING");
  }

  @Test
  void shouldNotCreateAlertForMediumOrLowRisk() {
    DailyResponse response = buildResponse(3, 3, "NONE", "NO_FEVER", null);

    when(riskRuleRepository.findByIsActiveTrue()).thenReturn(activeRules);
    when(dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId()))
        .thenReturn(List.of(response));
    when(riskScoreRepository.save(any(RiskScore.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RiskScore result = riskEngineService.calculateRiskScore(episode, response);

    assertThat(result.getRiskLevel()).isIn("LOW", "MEDIUM");
    verify(alertRepository, never()).save(any(Alert.class));
  }

  // ──────────────────────── Helper Methods ────────────────────────

  private DailyResponse buildResponse(
      int dayNumber, int painScore, String swellingLevel, String feverLevel, String[] dvtSymptoms) {
    return DailyResponse.builder()
        .id(UUID.randomUUID())
        .episode(episode)
        .dayNumber(dayNumber)
        .responderType("PATIENT")
        .painScore(painScore)
        .swellingLevel(swellingLevel)
        .feverLevel(feverLevel)
        .dvtSymptoms(dvtSymptoms)
        .completionStatus("COMPLETED")
        .build();
  }

  private RiskRule buildRule(
      String ruleName, String conditionExpression, String riskLevel, int weight) {
    return RiskRule.builder()
        .id(UUID.randomUUID())
        .ruleName(ruleName)
        .conditionExpression(conditionExpression)
        .riskLevel(riskLevel)
        .weight(weight)
        .isActive(true)
        .version(1)
        .build();
  }
}
