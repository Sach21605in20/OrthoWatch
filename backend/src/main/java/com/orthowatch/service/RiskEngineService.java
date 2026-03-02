package com.orthowatch.service;

import com.orthowatch.model.*;
import com.orthowatch.repository.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RiskEngineService {

  private static final Logger logger = LoggerFactory.getLogger(RiskEngineService.class);

  private static final int MAX_COMPOSITE_SCORE = 100;
  private static final int LOW_THRESHOLD = 30;
  private static final int MEDIUM_THRESHOLD = 60;
  private static final int TRAJECTORY_TOLERANCE = 5;
  private static final int TRAJECTORY_WINDOW = 3;

  private static final Map<String, Integer> SWELLING_ORDINAL =
      Map.of("NONE", 0, "MILD", 1, "MODERATE", 2, "SEVERE", 3);

  private final RiskRuleRepository riskRuleRepository;
  private final RiskScoreRepository riskScoreRepository;
  private final AlertRepository alertRepository;
  private final DailyResponseRepository dailyResponseRepository;

  /**
   * Calculate the composite risk score for an episode based on today's daily response.
   *
   * <p>Evaluates all active risk rules against the current response and historical data, computes a
   * composite score (0–100), classifies risk level, determines trajectory, and generates alerts for
   * HIGH risk.
   *
   * @param episode the care episode
   * @param todayResponse today's completed daily response
   * @return the persisted RiskScore entity
   */
  @Transactional
  public RiskScore calculateRiskScore(Episode episode, DailyResponse todayResponse) {
    List<RiskRule> activeRules = riskRuleRepository.findByIsActiveTrue();
    List<DailyResponse> history =
        dailyResponseRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId());

    // Evaluate each rule
    Map<String, Object> contributingFactors = new LinkedHashMap<>();
    int totalScore = 0;

    for (RiskRule rule : activeRules) {
      boolean triggered = evaluateRule(rule, todayResponse, history);
      if (triggered) {
        totalScore += rule.getWeight();
        contributingFactors.put(
            rule.getRuleName(),
            Map.of(
                "weight", rule.getWeight(),
                "riskLevel", rule.getRiskLevel(),
                "condition", rule.getConditionExpression()));
      }
    }

    int compositeScore = Math.min(totalScore, MAX_COMPOSITE_SCORE);
    String riskLevel = classifyRiskLevel(compositeScore);
    String trajectory = computeTrajectory(episode, compositeScore);
    String ruleVersionId = buildRuleVersionId(activeRules);
    Map<String, Object> ruleSetSnapshot = buildRuleSetSnapshot(activeRules);

    RiskScore riskScore =
        RiskScore.builder()
            .episode(episode)
            .dayNumber(todayResponse.getDayNumber())
            .compositeScore(compositeScore)
            .riskLevel(riskLevel)
            .contributingFactors(contributingFactors)
            .trajectory(trajectory)
            .ruleVersionId(ruleVersionId)
            .ruleSetSnapshot(ruleSetSnapshot)
            .calculatedAt(OffsetDateTime.now())
            .build();

    RiskScore saved = riskScoreRepository.save(riskScore);

    logger.info(
        "Risk score calculated: episodeId={}, day={}, score={}, level={}, trajectory={}",
        episode.getId(),
        todayResponse.getDayNumber(),
        compositeScore,
        riskLevel,
        trajectory);

    if ("HIGH".equals(riskLevel)) {
      createHighRiskAlert(episode, saved);
    }

    return saved;
  }

  // ──────────────────────── Rule Evaluation ────────────────────────

  private boolean evaluateRule(
      RiskRule rule, DailyResponse todayResponse, List<DailyResponse> history) {
    return switch (rule.getConditionExpression()) {
      case "FEVER_ABOVE_100" -> evaluateFeverRule(todayResponse);
      case "DVT_ANY_PRESENT" -> evaluateDvtRule(todayResponse);
      case "PAIN_SPIKE_GT_2" -> evaluatePainSpikeRule(todayResponse, history);
      case "SWELLING_INCREASING_2D" -> evaluateSwellingTrendRule(todayResponse, history);
      case "WOUND_REDNESS_DISCHARGE" -> false; // Deferred to wound image analysis phase
      default -> {
        logger.warn("Unknown condition expression: {}", rule.getConditionExpression());
        yield false;
      }
    };
  }

  private boolean evaluateFeverRule(DailyResponse response) {
    String fever = response.getFeverLevel();
    return "100_TO_102".equals(fever) || "ABOVE_102".equals(fever);
  }

  private boolean evaluateDvtRule(DailyResponse response) {
    String[] dvt = response.getDvtSymptoms();
    return dvt != null && dvt.length > 0;
  }

  private boolean evaluatePainSpikeRule(DailyResponse today, List<DailyResponse> history) {
    if (today.getPainScore() == null) return false;

    Optional<DailyResponse> yesterday =
        history.stream()
            .filter(r -> r.getDayNumber() == today.getDayNumber() - 1)
            .findFirst();

    if (yesterday.isEmpty() || yesterday.get().getPainScore() == null) return false;

    int delta = today.getPainScore() - yesterday.get().getPainScore();
    return delta > 2;
  }

  private boolean evaluateSwellingTrendRule(DailyResponse today, List<DailyResponse> history) {
    if (today.getSwellingLevel() == null) return false;

    // Need at least 2 previous days to detect a 2-day increasing trend
    Optional<DailyResponse> yesterday =
        history.stream()
            .filter(r -> r.getDayNumber() == today.getDayNumber() - 1)
            .findFirst();

    Optional<DailyResponse> twoDaysAgo =
        history.stream()
            .filter(r -> r.getDayNumber() == today.getDayNumber() - 2)
            .findFirst();

    if (yesterday.isEmpty()
        || twoDaysAgo.isEmpty()
        || yesterday.get().getSwellingLevel() == null
        || twoDaysAgo.get().getSwellingLevel() == null) {
      return false;
    }

    int ordinalToday = SWELLING_ORDINAL.getOrDefault(today.getSwellingLevel(), 0);
    int ordinalYesterday = SWELLING_ORDINAL.getOrDefault(yesterday.get().getSwellingLevel(), 0);
    int ordinalTwoDaysAgo = SWELLING_ORDINAL.getOrDefault(twoDaysAgo.get().getSwellingLevel(), 0);

    return ordinalToday > ordinalYesterday && ordinalYesterday > ordinalTwoDaysAgo;
  }

  // ──────────────────────── Risk Level Classification ────────────────────────

  private String classifyRiskLevel(int compositeScore) {
    if (compositeScore <= LOW_THRESHOLD) return "LOW";
    if (compositeScore <= MEDIUM_THRESHOLD) return "MEDIUM";
    return "HIGH";
  }

  // ──────────────────────── Trajectory ────────────────────────

  private String computeTrajectory(Episode episode, int todayScore) {
    List<RiskScore> previousScores =
        riskScoreRepository.findByEpisodeIdOrderByDayNumberDesc(episode.getId());

    if (previousScores.isEmpty()) {
      return null; // Day 1, no trend data
    }

    // Take the most recent previous score for comparison
    // With 3-day window: compare today vs last 2 recorded scores
    if (previousScores.size() >= 2) {
      int prev1 = previousScores.get(0).getCompositeScore();
      int prev2 = previousScores.get(1).getCompositeScore();

      // Average of previous two vs today
      double avgPrevious = (prev1 + prev2) / 2.0;
      double delta = todayScore - avgPrevious;

      if (delta < -TRAJECTORY_TOLERANCE) return "IMPROVING";
      if (delta > TRAJECTORY_TOLERANCE) return "WORSENING";
      return "STABLE";
    }

    // Only 1 previous score
    int prevScore = previousScores.get(0).getCompositeScore();
    int delta = todayScore - prevScore;

    if (delta < -TRAJECTORY_TOLERANCE) return "IMPROVING";
    if (delta > TRAJECTORY_TOLERANCE) return "WORSENING";
    return "STABLE";
  }

  // ──────────────────────── Rule Version Snapshot ────────────────────────

  private String buildRuleVersionId(List<RiskRule> rules) {
    return rules.stream()
        .map(r -> r.getRuleName() + ":v" + r.getVersion())
        .sorted()
        .collect(Collectors.joining(","));
  }

  private Map<String, Object> buildRuleSetSnapshot(List<RiskRule> rules) {
    List<Map<String, Object>> ruleList =
        rules.stream()
            .map(
                r ->
                    Map.<String, Object>of(
                        "ruleName", r.getRuleName(),
                        "conditionExpression", r.getConditionExpression(),
                        "riskLevel", r.getRiskLevel(),
                        "weight", r.getWeight(),
                        "version", r.getVersion()))
            .toList();

    return Map.of("rules", ruleList, "snapshotAt", OffsetDateTime.now().toString());
  }

  // ──────────────────────── Alert Generation ────────────────────────

  private void createHighRiskAlert(Episode episode, RiskScore riskScore) {
    Alert alert =
        Alert.builder()
            .episode(episode)
            .riskScore(riskScore)
            .alertType("HIGH_RISK")
            .severity("HIGH")
            .assignedTo(episode.getPrimarySurgeon())
            .status("PENDING")
            .slaDeadline(OffsetDateTime.now().plusHours(2))
            .build();

    alertRepository.save(alert);

    logger.info(
        "HIGH risk alert created: episodeId={}, riskScoreId={}, assignedTo={}",
        episode.getId(),
        riskScore.getId(),
        episode.getPrimarySurgeon().getEmail());
  }
}
