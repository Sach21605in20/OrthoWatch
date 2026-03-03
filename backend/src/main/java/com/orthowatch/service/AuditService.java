package com.orthowatch.service;

import com.orthowatch.model.ClinicalAuditLog;
import com.orthowatch.model.User;
import com.orthowatch.repository.ClinicalAuditLogRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable audit logger for clinical actions (NABH compliance).
 *
 * <p>Every clinician action (acknowledge, resolve, view, enroll, etc.) is recorded here. Entries
 * are append-only — no update or delete methods exist by design.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

  private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

  private final ClinicalAuditLogRepository auditLogRepository;

  /**
   * Create an immutable audit log entry.
   *
   * @param user the clinician performing the action
   * @param episodeId related episode (nullable for non-episode actions like LOGIN)
   * @param action the action performed (ACKNOWLEDGE_ALERT, RESOLVE_ALERT, AUTO_FORWARD_ALERT, etc.)
   * @param resourceType entity type (ALERT, PATIENT, TEMPLATE, etc.) — nullable
   * @param resourceId ID of affected resource — nullable
   * @param riskScoreAtAction patient risk score at time of action — nullable
   * @param details additional context as JSON — nullable
   * @param ipAddress clinician IP address — nullable
   * @param userAgent browser/device info — nullable
   */
  @Transactional
  public void log(
      User user,
      UUID episodeId,
      String action,
      String resourceType,
      UUID resourceId,
      Integer riskScoreAtAction,
      Map<String, Object> details,
      String ipAddress,
      String userAgent) {

    ClinicalAuditLog entry =
        ClinicalAuditLog.builder()
            .user(user)
            .episodeId(episodeId)
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .riskScoreAtAction(riskScoreAtAction)
            .details(details)
            .ipAddress(ipAddress)
            .userAgent(userAgent)
            .build();

    auditLogRepository.save(entry);

    logger.info(
        "Audit log: user={}, action={}, resourceType={}, resourceId={}",
        user.getEmail(),
        action,
        resourceType,
        resourceId);
  }
}
