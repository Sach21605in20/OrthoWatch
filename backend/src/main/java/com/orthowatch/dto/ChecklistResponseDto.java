package com.orthowatch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChecklistResponseDto {

  @Min(value = 0, message = "Pain score must be between 0 and 10")
  @Max(value = 10, message = "Pain score must be between 0 and 10")
  private Integer painScore;

  @Pattern(
      regexp = "^(NONE|MILD|MODERATE|SEVERE)$",
      message = "Swelling level must be one of: NONE, MILD, MODERATE, SEVERE")
  private String swellingLevel;

  @Pattern(
      regexp = "^(NO_FEVER|BELOW_100|100_TO_102|ABOVE_102)$",
      message = "Fever level must be one of: NO_FEVER, BELOW_100, 100_TO_102, ABOVE_102")
  private String feverLevel;

  private String[] dvtSymptoms;

  private Boolean mobilityAchieved;

  @Pattern(
      regexp = "^(TOOK_ALL|MISSED_SOME|DIDNT_TAKE)$",
      message = "Medication adherence must be one of: TOOK_ALL, MISSED_SOME, DIDNT_TAKE")
  private String medicationAdherence;

  @NotBlank(message = "Responder type is required")
  @Pattern(
      regexp = "^(PATIENT|CAREGIVER)$",
      message = "Responder type must be PATIENT or CAREGIVER")
  private String responderType;
}
