package com.orthowatch.repository;

import com.orthowatch.model.Episode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {
  List<Episode> findByPatientId(UUID patientId);

  List<Episode> findByPrimarySurgeonId(UUID primarySurgeonId);

  List<Episode> findByStatus(String status);

  boolean existsByPatientIdAndTemplateSurgeryTypeAndStatus(
      UUID patientId, String surgeryType, String status);

  List<Episode> findByStatusAndConsentStatus(String status, String consentStatus);

  List<Episode> findByConsentStatusAndCreatedAtBefore(String consentStatus, OffsetDateTime cutoff);

  java.util.Optional<Episode> findByPatientIdAndStatus(UUID patientId, String status);
}
