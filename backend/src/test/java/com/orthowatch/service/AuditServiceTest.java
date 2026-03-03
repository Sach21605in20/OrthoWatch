package com.orthowatch.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.orthowatch.model.ClinicalAuditLog;
import com.orthowatch.model.User;
import com.orthowatch.repository.ClinicalAuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

  @Mock private ClinicalAuditLogRepository auditLogRepository;

  @InjectMocks private AuditService auditService;

  @Test
  void shouldCreateAuditLogEntry() {
    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("surgeon@orthowatch.com")
            .fullName("Dr. Surgeon")
            .role("SURGEON")
            .build();

    UUID episodeId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    Map<String, Object> details = Map.of("alertType", "HIGH_RISK");

    when(auditLogRepository.save(any(ClinicalAuditLog.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    auditService.log(
        user,
        episodeId,
        "ACKNOWLEDGE_ALERT",
        "ALERT",
        resourceId,
        75,
        details,
        "192.168.1.1",
        "Mozilla/5.0");

    ArgumentCaptor<ClinicalAuditLog> captor = ArgumentCaptor.forClass(ClinicalAuditLog.class);
    verify(auditLogRepository).save(captor.capture());

    ClinicalAuditLog saved = captor.getValue();
    assertThat(saved.getUser()).isEqualTo(user);
    assertThat(saved.getEpisodeId()).isEqualTo(episodeId);
    assertThat(saved.getAction()).isEqualTo("ACKNOWLEDGE_ALERT");
    assertThat(saved.getResourceType()).isEqualTo("ALERT");
    assertThat(saved.getResourceId()).isEqualTo(resourceId);
    assertThat(saved.getRiskScoreAtAction()).isEqualTo(75);
    assertThat(saved.getDetails()).containsEntry("alertType", "HIGH_RISK");
    assertThat(saved.getIpAddress()).isEqualTo("192.168.1.1");
    assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
  }

  @Test
  void shouldAcceptNullOptionalFields() {
    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("admin@orthowatch.com")
            .fullName("Admin")
            .role("ADMIN")
            .build();

    when(auditLogRepository.save(any(ClinicalAuditLog.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    auditService.log(user, null, "LOGIN", null, null, null, null, null, null);

    ArgumentCaptor<ClinicalAuditLog> captor = ArgumentCaptor.forClass(ClinicalAuditLog.class);
    verify(auditLogRepository).save(captor.capture());

    ClinicalAuditLog saved = captor.getValue();
    assertThat(saved.getUser()).isEqualTo(user);
    assertThat(saved.getAction()).isEqualTo("LOGIN");
    assertThat(saved.getEpisodeId()).isNull();
    assertThat(saved.getResourceType()).isNull();
    assertThat(saved.getResourceId()).isNull();
    assertThat(saved.getRiskScoreAtAction()).isNull();
    assertThat(saved.getDetails()).isNull();
    assertThat(saved.getIpAddress()).isNull();
    assertThat(saved.getUserAgent()).isNull();
  }
}
