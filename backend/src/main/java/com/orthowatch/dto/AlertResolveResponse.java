package com.orthowatch.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertResolveResponse {
  private UUID alertId;
  private String status;
  private String escalationOutcome;
  private OffsetDateTime resolvedAt;
}
