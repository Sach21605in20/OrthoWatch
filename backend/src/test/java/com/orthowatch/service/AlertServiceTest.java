package com.orthowatch.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.orthowatch.dto.AlertResolveRequest;
import com.orthowatch.exception.ResourceNotFoundException;
import com.orthowatch.model.Alert;
import com.orthowatch.model.Episode;
import com.orthowatch.model.User;
import com.orthowatch.repository.AlertRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

  @Mock private AlertRepository alertRepository;
  @Mock private AuditService auditService;

  @InjectMocks private AlertService alertService;

  private User surgeon;
  private User nurse;
  private Episode episode;
  private Alert pendingAlert;

  @BeforeEach
  void setUp() {
    surgeon =
        User.builder()
            .id(UUID.randomUUID())
            .email("surgeon@orthowatch.com")
            .fullName("Dr. Surgeon")
            .role("SURGEON")
            .build();

    nurse =
        User.builder()
            .id(UUID.randomUUID())
            .email("nurse@orthowatch.com")
            .fullName("Nurse Priya")
            .role("NURSE")
            .build();

    episode =
        Episode.builder()
            .id(UUID.randomUUID())
            .primarySurgeon(surgeon)
            .secondaryClinician(nurse)
            .build();

    pendingAlert =
        Alert.builder()
            .id(UUID.randomUUID())
            .episode(episode)
            .alertType("HIGH_RISK")
            .severity("HIGH")
            .assignedTo(surgeon)
            .status("PENDING")
            .slaDeadline(OffsetDateTime.now().plusHours(2))
            .build();
  }

  @Test
  void shouldAcknowledgePendingAlert() {
    when(alertRepository.findById(pendingAlert.getId())).thenReturn(Optional.of(pendingAlert));
    when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

    Alert result = alertService.acknowledge(pendingAlert.getId(), surgeon);

    assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
    assertThat(result.getAcknowledgedAt()).isNotNull();
    verify(auditService)
        .log(eq(surgeon), eq(episode.getId()), eq("ACKNOWLEDGE_ALERT"), eq("ALERT"),
            eq(pendingAlert.getId()), isNull(), anyMap(), isNull(), isNull());
  }

  @Test
  void shouldThrowWhenAcknowledgingNonExistentAlert() {
    UUID fakeId = UUID.randomUUID();
    when(alertRepository.findById(fakeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> alertService.acknowledge(fakeId, surgeon))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(fakeId.toString());
  }

  @Test
  void shouldThrowWhenAcknowledgingNonPendingAlert() {
    pendingAlert.setStatus("RESOLVED");
    when(alertRepository.findById(pendingAlert.getId())).thenReturn(Optional.of(pendingAlert));

    assertThatThrownBy(() -> alertService.acknowledge(pendingAlert.getId(), surgeon))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RESOLVED");
  }

  @Test
  void shouldResolveAcknowledgedAlert() {
    pendingAlert.setStatus("ACKNOWLEDGED");
    pendingAlert.setAcknowledgedAt(OffsetDateTime.now());

    when(alertRepository.findById(pendingAlert.getId())).thenReturn(Optional.of(pendingAlert));
    when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

    AlertResolveRequest request =
        AlertResolveRequest.builder()
            .escalationOutcome("OPD_SCHEDULED")
            .notes("Patient advised to visit OPD tomorrow.")
            .build();

    Alert result = alertService.resolve(pendingAlert.getId(), request, surgeon);

    assertThat(result.getStatus()).isEqualTo("RESOLVED");
    assertThat(result.getResolvedAt()).isNotNull();
    assertThat(result.getEscalationOutcome()).isEqualTo("OPD_SCHEDULED");
    assertThat(result.getEscalationNotes()).isEqualTo("Patient advised to visit OPD tomorrow.");
    verify(auditService)
        .log(eq(surgeon), eq(episode.getId()), eq("RESOLVE_ALERT"), eq("ALERT"),
            eq(pendingAlert.getId()), isNull(), anyMap(), isNull(), isNull());
  }

  @Test
  void shouldThrowWhenResolvingNonAcknowledgedAlert() {
    // pendingAlert is still in PENDING status
    when(alertRepository.findById(pendingAlert.getId())).thenReturn(Optional.of(pendingAlert));

    AlertResolveRequest request =
        AlertResolveRequest.builder().escalationOutcome("FALSE_POSITIVE").build();

    assertThatThrownBy(() -> alertService.resolve(pendingAlert.getId(), request, surgeon))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PENDING");
  }

  @Test
  void shouldAutoForwardExpiredAlerts() {
    pendingAlert.setSlaDeadline(OffsetDateTime.now().minusMinutes(10));
    when(alertRepository.findByStatusAndSlaDeadlineBefore(eq("PENDING"), any(OffsetDateTime.class)))
        .thenReturn(List.of(pendingAlert));
    when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

    alertService.autoForwardExpiredAlerts();

    assertThat(pendingAlert.getAssignedTo()).isEqualTo(nurse);
    assertThat(pendingAlert.isAutoForwarded()).isTrue();
    assertThat(pendingAlert.getSlaDeadline()).isAfter(OffsetDateTime.now().minusMinutes(1));
    verify(auditService)
        .log(eq(surgeon), eq(episode.getId()), eq("AUTO_FORWARD_ALERT"), eq("ALERT"),
            eq(pendingAlert.getId()), isNull(), anyMap(), isNull(), isNull());
  }

  @Test
  void shouldSkipAutoForwardWhenNoSecondaryClinician() {
    episode.setSecondaryClinician(null);
    pendingAlert.setSlaDeadline(OffsetDateTime.now().minusMinutes(10));

    when(alertRepository.findByStatusAndSlaDeadlineBefore(eq("PENDING"), any(OffsetDateTime.class)))
        .thenReturn(List.of(pendingAlert));

    alertService.autoForwardExpiredAlerts();

    // Should NOT save or audit — just log warning
    verify(alertRepository, never()).save(any());
    verify(auditService, never()).log(any(), any(), any(), any(), any(), any(), any(), any(), any());
    assertThat(pendingAlert.getAssignedTo()).isEqualTo(surgeon); // unchanged
  }
}
