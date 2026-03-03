package com.orthowatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertResolveRequest {

  @NotBlank(message = "Escalation outcome is required")
  @Pattern(
      regexp =
          "OPD_SCHEDULED|TELEPHONIC_ADVICE|MEDICATION_ADJUSTED|ER_REFERRAL|FALSE_POSITIVE",
      message =
          "Escalation outcome must be one of: OPD_SCHEDULED, TELEPHONIC_ADVICE,"
              + " MEDICATION_ADJUSTED, ER_REFERRAL, FALSE_POSITIVE")
  private String escalationOutcome;

  @Size(max = 2000, message = "Notes must be at most 2000 characters")
  private String notes;
}
