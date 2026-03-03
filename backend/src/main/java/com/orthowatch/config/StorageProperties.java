package com.orthowatch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration properties for wound image storage. */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

  /** Storage provider: LOCAL or SUPABASE. */
  private String provider = "LOCAL";

  /** Local filesystem path for dev storage. */
  private String localPath = "./uploads/wound-images";

  /** Maximum file size in bytes (default 10 MB). */
  private long maxFileSize = 10_485_760L;
}
