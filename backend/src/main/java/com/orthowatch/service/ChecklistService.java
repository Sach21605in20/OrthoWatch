package com.orthowatch.service;

import com.orthowatch.dto.ChecklistResponseDto;
import com.orthowatch.exception.ResourceNotFoundException;
import com.orthowatch.model.Alert;
import com.orthowatch.model.DailyResponse;
import com.orthowatch.model.Episode;
import com.orthowatch.model.RecoveryTemplate;
import com.orthowatch.repository.AlertRepository;
import com.orthowatch.repository.DailyResponseRepository;
import com.orthowatch.repository.EpisodeRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChecklistService {

  private static final Logger logger = LoggerFactory.getLogger(ChecklistService.class);

  private final EpisodeRepository episodeRepository;
  private final DailyResponseRepository dailyResponseRepository;
  private final AlertRepository alertRepository;
  private final RiskEngineService riskEngineService;

  /**
   * Process a checklist response for a given episode and day.
   *
   * <p>Creates or updates the DailyResponse, determines completion status based on the template's
   * required fields for this day, and triggers risk engine scoring when complete.
   *
   * @param episodeId the episode UUID
   * @param dayNumber the day number within the monitoring period
   * @param dto the incoming checklist response data
   * @return the saved DailyResponse
   */
  @Transactional
  public DailyResponse processResponse(UUID episodeId, int dayNumber, ChecklistResponseDto dto) {
    Episode episode =
        episodeRepository
            .findById(episodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Episode not found: " + episodeId));

    DailyResponse response =
        dailyResponseRepository
            .findByEpisodeIdAndDayNumber(episodeId, dayNumber)
            .orElseGet(
                () ->
                    DailyResponse.builder()
                        .episode(episode)
                        .dayNumber(dayNumber)
                        .responderType(dto.getResponderType())
                        .completionStatus("PENDING")
                        .build());

    // Map DTO fields to entity
    if (dto.getPainScore() != null) response.setPainScore(dto.getPainScore());
    if (dto.getSwellingLevel() != null) response.setSwellingLevel(dto.getSwellingLevel());
    if (dto.getFeverLevel() != null) response.setFeverLevel(dto.getFeverLevel());
    if (dto.getDvtSymptoms() != null) response.setDvtSymptoms(dto.getDvtSymptoms());
    if (dto.getMobilityAchieved() != null) response.setMobilityAchieved(dto.getMobilityAchieved());
    if (dto.getMedicationAdherence() != null)
      response.setMedicationAdherence(dto.getMedicationAdherence());
    if (dto.getResponderType() != null) response.setResponderType(dto.getResponderType());

    // Determine completion status
    String completionStatus = determineCompletionStatus(response, episode);
    response.setCompletionStatus(completionStatus);

    if ("COMPLETED".equals(completionStatus)) {
      response.setResponseCompletedAt(OffsetDateTime.now());
    }

    if (response.getResponseStartedAt() == null) {
      response.setResponseStartedAt(OffsetDateTime.now());
    }

    DailyResponse saved = dailyResponseRepository.save(response);

    logger.info(
        "Checklist response processed: episodeId={}, day={}, status={}",
        episodeId,
        dayNumber,
        completionStatus);

    // Trigger risk engine on completion
    if ("COMPLETED".equals(completionStatus)) {
      riskEngineService.calculateRiskScore(episode, saved);
      logger.info("Risk engine triggered for episodeId={}, day={}", episodeId, dayNumber);
    }

    return saved;
  }

  /**
   * Handle a late response — if a NON_RESPONSE alert exists and is still PENDING, cancel it.
   *
   * @param episodeId the episode UUID
   * @param dayNumber the day number
   */
  @Transactional
  public void handleLateResponse(UUID episodeId, int dayNumber) {
    List<Alert> alerts = alertRepository.findByEpisodeId(episodeId);

    alerts.stream()
        .filter(a -> "NON_RESPONSE".equals(a.getAlertType()) && "PENDING".equals(a.getStatus()))
        .forEach(
            alert -> {
              alert.setStatus("CANCELLED");
              alertRepository.save(alert);
              logger.info(
                  "NON_RESPONSE alert cancelled due to late response: alertId={}, episodeId={}",
                  alert.getId(),
                  episodeId);
            });
  }

  /**
   * Determine the completion status based on the template's required questions for this day.
   *
   * <p>COMPLETED = all required fields present. PARTIAL = some fields present. PENDING = no fields.
   */
  @SuppressWarnings("unchecked")
  String determineCompletionStatus(DailyResponse response, Episode episode) {
    RecoveryTemplate template = episode.getTemplate();
    Map<String, Object> checklistConfig = template.getChecklistConfig();
    Map<String, Object> daysConfig = (Map<String, Object>) checklistConfig.get("days");

    if (daysConfig == null) return "COMPLETED";

    String dayKey = String.valueOf(response.getDayNumber());
    Map<String, Object> dayConfig = (Map<String, Object>) daysConfig.get(dayKey);

    if (dayConfig == null) return "COMPLETED";

    List<String> requiredQuestions = (List<String>) dayConfig.get("questions");

    if (requiredQuestions == null || requiredQuestions.isEmpty()) return "COMPLETED";

    int totalRequired = requiredQuestions.size();
    int answered = 0;

    for (String question : requiredQuestions) {
      if (isQuestionAnswered(question, response)) {
        answered++;
      }
    }

    if (answered == 0) return "PENDING";
    if (answered >= totalRequired) return "COMPLETED";
    return "PARTIAL";
  }

  private boolean isQuestionAnswered(String question, DailyResponse response) {
    return switch (question) {
      case "pain_score" -> response.getPainScore() != null;
      case "swelling_level" -> response.getSwellingLevel() != null;
      case "fever_check" -> response.getFeverLevel() != null;
      case "dvt_symptoms" -> response.getDvtSymptoms() != null && response.getDvtSymptoms().length > 0;
      case "mobility_check" -> response.getMobilityAchieved() != null;
      case "medication_adherence" -> response.getMedicationAdherence() != null;
      case "wound_image" -> true; // Wound image check deferred to Phase 4
      default -> false;
    };
  }
}
