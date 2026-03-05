package com.orthowatch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.whatsapp")
public class WhatsAppProperties {

  private String apiUrl = "https://graph.facebook.com/v18.0";

  private String phoneNumberId;

  private String accessToken;

  private String verifyToken;
}
