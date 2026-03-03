package com.orthowatch.service;

import com.orthowatch.dto.AlertResolveRequest;
import com.orthowatch.exception.ResourceNotFoundException;
import com.orthowatch.model.Alert;
import com.orthowatch.model.Episode;
import com.orthowatch.model.User;
import com.orthowatch.repository.AlertRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the alert lifecycle: PENDING → ACKNOWLEDGED → RESOLVED.
 *
 * <p>Also handles SLA auto-forwarding of expired PENDING alerts to the secondary clinician.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

  private static final Logger logger = LoggerFactory.getLogger(AlertService.class);

  private final AlertRepository alertRepository;
  private final AuditService auditService;

  /**
   * Acknowledge a PENDING alert. Sets status to ACKNOWLEDGED and records the timestamp.
   *
   * @param alertId the alert to acknowledge
   * @param user the clinician acknowledging
   * @return the updated Alert
   * @throws ResourceNotFoundException if alert not found
   * @throws IllegalStateException if alert is not in PENDING status
   */
  @Transactional
  public Alert acknowledge(java.util.UUID alertId, User user) {
    Alert alert =
        alertRepository
            .findById(alertId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Alert not found: " + alertId));

    if (!"PENDING".equals(alert.getStatus())) {
      throw new IllegalStateException(
          "Cannot acknowledge alert in status: " + alert.getStatus());
    }

    alert.setStatus("ACKNOWLEDGED");
    alert.setAcknowledgedAt(OffsetDateTime.now());
    Alert saved = alertRepository.save(alert);

    auditService.log(
        user,
        alert.getEpisode().getId(),
        "ACKNOWLEDGE_ALERT",
        "ALERT",
        alertId,
        null,
        Map.of("alertType", alert.getAlertType(), "severity", alert.getSeverity()),
        null,
        null);

    logger.info(
        "Alert acknowledged: alertId={}, by={}", alertId, user.getEmail());

    return saved;
  }

  /**
   * Resolve an ACKNOWLEDGED alert with an escalation outcome.
   *
   * @param alertId the alert to resolve
   * @param request the resolve request containing outcome and optional notes
   * @param user the clinician resolving
   * @return the updated Alert
   * @throws ResourceNotFoundException if alert not found
   * @throws IllegalStateException if alert is not in ACKNOWLEDGED status
   */
  @Transactional
  public Alert resolve(java.util.UUID alertId, AlertResolveRequest request, User user) {
    Alert alert =
        alertRepository
            .findById(alertId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Alert not found: " + alertId));

    if (!"ACKNOWLEDGED".equals(alert.getStatus())) {
      throw new IllegalStateException(
          "Cannot resolve alert in status: " + alert.getStatus());
    }

    alert.setStatus("RESOLVED");
    alert.setResolvedAt(OffsetDateTime.now());
    alert.setEscalationOutcome(request.getEscalationOutcome());
    alert.setEscalationNotes(request.getNotes());
    Alert saved = alertRepository.save(alert);

    auditService.log(
        user,
        alert.getEpisode().getId(),
        "RESOLVE_ALERT",
        "ALERT",
        alertId,
        null,
        Map.of(
            "alertType", alert.getAlertType(),
            "escalationOutcome", request.getEscalationOutcome(),
            "notes", request.getNotes() != null ? request.getNotes() : ""),
        null,
        null);

    logger.info(
        "Alert resolved: alertId={}, outcome={}, by={}",
        alertId,
        request.getEscalationOutcome(),
        user.getEmail());

    return saved;
  }

  /**
   * Auto-forward PENDING alerts that have exceeded their SLA deadline. Re-assigns the alert to the
   * episode's secondary clinician (if set), marks it as auto-forwarded, and sets a new SLA
   * deadline.
   */
  @Transactional
  public void autoForwardExpiredAlerts() {
    OffsetDateTime now = OffsetDateTime.now();
    List<Alert> expiredAlerts =
        alertRepository.findByStatusAndSlaDeadlineBefore("PENDING", now);

    for (Alert alert : expiredAlerts) {
      Episode episode = alert.getEpisode();
      User secondaryClinician = episode.getSecondaryClinician();

      if (secondaryClinician == null) {
        logger.warn(
            "Cannot auto-forward alert {} — no secondary clinician on episode {}",
            alert.getId(),
            episode.getId());
        continue;
      }

      User previousAssignee = alert.getAssignedTo();
      alert.setAssignedTo(secondaryClinician);
      alert.setAutoForwarded(true);
      alert.setSlaDeadline(now.plusHours(2));
      alertRepository.save(alert);

      auditService.log(
          previousAssignee,
          episode.getId(),
          "AUTO_FORWARD_ALERT",
          "ALERT",
          alert.getId(),
          null,
          Map.of(
              "alertType", alert.getAlertType(),
              "previousAssignee", previousAssignee.getEmail(),
              "newAssignee", secondaryClinician.getEmail()),
          null,
          null);

      logger.info(
          "Alert auto-forwarded: alertId={}, from={} to={}",
          alert.getId(),
          previousAssignee.getEmail(),
          secondaryClinician.getEmail());
    }
  }
}
