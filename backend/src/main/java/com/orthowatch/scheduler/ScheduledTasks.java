package com.orthowatch.scheduler;

import com.orthowatch.model.Alert;
import com.orthowatch.model.DailyResponse;
import com.orthowatch.model.Episode;
import com.orthowatch.repository.AlertRepository;
import com.orthowatch.repository.DailyResponseRepository;
import com.orthowatch.repository.EpisodeRepository;
import com.orthowatch.repository.SessionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled tasks for daily checklist dispatch, reminders, escalation, consent timeout, and session
 * cleanup.
 *
 * <p>Uses Spring {@code @Scheduled} for MVP (single hospital, IST timezone). Quartz with JDBC job
 * store deferred to post-pilot for multi-hospital/multi-timezone support.
 */
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

  private static final Logger logger = LoggerFactory.getLogger(ScheduledTasks.class);

  private static final long REMINDER_HOURS = 4;
  private static final long ESCALATION_HOURS = 8;
  private static final long CONSENT_TIMEOUT_HOURS = 24;

  private final EpisodeRepository episodeRepository;
  private final DailyResponseRepository dailyResponseRepository;
  private final AlertRepository alertRepository;
  private final SessionRepository sessionRepository;

  /**
   * Dispatch daily checklists at 9 AM IST for all active episodes with granted consent.
   *
   * <p>Increments {@code currentDay}, creates a PENDING {@link DailyResponse}, and logs which
   * patients need checklists. Actual WhatsApp message dispatch is deferred to Phase 4.
   */
  @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void dispatchDailyChecklists() {
    List<Episode> activeEpisodes =
        episodeRepository.findByStatusAndConsentStatus("ACTIVE", "GRANTED");

    logger.info("Dispatching daily checklists for {} active episodes", activeEpisodes.size());

    for (Episode episode : activeEpisodes) {
      int nextDay = episode.getCurrentDay() + 1;
      int monitoringDays = episode.getTemplate().getMonitoringDays();

      if (nextDay > monitoringDays) {
        episode.setStatus("COMPLETED");
        episodeRepository.save(episode);
        logger.info(
            "Episode completed: episodeId={}, monitoringDays={}",
            episode.getId(),
            monitoringDays);
        continue;
      }

      episode.setCurrentDay(nextDay);
      episodeRepository.save(episode);

      // Create PENDING DailyResponse for today
      DailyResponse pendingResponse =
          DailyResponse.builder()
              .episode(episode)
              .dayNumber(nextDay)
              .responderType("PATIENT")
              .completionStatus("PENDING")
              .build();

      dailyResponseRepository.save(pendingResponse);

      logger.info(
          "Checklist dispatched: episodeId={}, day={}, patientPhone={}",
          episode.getId(),
          nextDay,
          episode.getPatient().getPhonePrimary());
    }
  }

  /**
   * Check for PENDING responses older than 4 hours and log reminder notices.
   *
   * <p>Actual WhatsApp reminder messages deferred to Phase 4.
   */
  @Scheduled(fixedRate = 60000)
  public void sendReminders() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusHours(REMINDER_HOURS);
    List<DailyResponse> staleResponses =
        dailyResponseRepository.findByCompletionStatusAndCreatedAtBefore("PENDING", cutoff);

    for (DailyResponse response : staleResponses) {
      logger.info(
          "Reminder needed: episodeId={}, day={}, pendingSince={}",
          response.getEpisode().getId(),
          response.getDayNumber(),
          response.getCreatedAt());
    }
  }

  /**
   * Escalate non-responses after 8 hours by creating a NON_RESPONSE alert assigned to the primary
   * surgeon.
   */
  @Scheduled(fixedRate = 60000)
  @Transactional
  public void escalateNonResponses() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusHours(ESCALATION_HOURS);
    List<DailyResponse> staleResponses =
        dailyResponseRepository.findByCompletionStatusAndCreatedAtBefore("PENDING", cutoff);

    for (DailyResponse response : staleResponses) {
      Episode episode = response.getEpisode();

      // Check if alert already exists for this episode
      boolean alertExists =
          alertRepository.findByEpisodeId(episode.getId()).stream()
              .anyMatch(
                  a ->
                      "NON_RESPONSE".equals(a.getAlertType())
                          && "PENDING".equals(a.getStatus()));

      if (!alertExists) {
        Alert alert =
            Alert.builder()
                .episode(episode)
                .alertType("NON_RESPONSE")
                .severity("MEDIUM")
                .assignedTo(episode.getPrimarySurgeon())
                .status("PENDING")
                .slaDeadline(OffsetDateTime.now().plusHours(2))
                .build();

        alertRepository.save(alert);

        logger.info(
            "NON_RESPONSE alert created: episodeId={}, day={}, assignedTo={}",
            episode.getId(),
            response.getDayNumber(),
            episode.getPrimarySurgeon().getEmail());
      }
    }
  }

  /**
   * Check for episodes with PENDING consent older than 24 hours and create CONSENT_TIMEOUT alerts.
   */
  @Scheduled(fixedRate = 300000)
  @Transactional
  public void checkConsentTimeouts() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusHours(CONSENT_TIMEOUT_HOURS);
    List<Episode> timedOutEpisodes =
        episodeRepository.findByConsentStatusAndCreatedAtBefore("PENDING", cutoff);

    for (Episode episode : timedOutEpisodes) {
      boolean alertExists =
          alertRepository.findByEpisodeId(episode.getId()).stream()
              .anyMatch(
                  a ->
                      "CONSENT_TIMEOUT".equals(a.getAlertType())
                          && "PENDING".equals(a.getStatus()));

      if (!alertExists) {
        Alert alert =
            Alert.builder()
                .episode(episode)
                .alertType("CONSENT_TIMEOUT")
                .severity("HIGH")
                .assignedTo(episode.getPrimarySurgeon())
                .status("PENDING")
                .build();

        alertRepository.save(alert);

        logger.info(
            "CONSENT_TIMEOUT alert created: episodeId={}, assignedTo={}",
            episode.getId(),
            episode.getPrimarySurgeon().getEmail());
      }
    }
  }

  /** Delete expired sessions daily at 2 AM IST. */
  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void cleanupExpiredSessions() {
    OffsetDateTime now = OffsetDateTime.now();
    sessionRepository.deleteByExpiresAtBefore(now);
    logger.info("Expired sessions cleaned up at {}", now);
  }
}
