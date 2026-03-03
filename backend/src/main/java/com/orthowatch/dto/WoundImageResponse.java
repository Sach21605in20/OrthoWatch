package com.orthowatch.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response DTO for wound image metadata. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WoundImageResponse {

  private UUID id;
  private UUID episodeId;
  private int dayNumber;
  private String contentType;
  private long fileSizeBytes;
  private boolean isMandatory;
  private String uploadedBy;
  private OffsetDateTime createdAt;
}
