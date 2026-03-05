package com.orthowatch.service;

import com.orthowatch.config.WhatsAppProperties;
import com.orthowatch.dto.whatsapp.WhatsAppMessageRequest;
import com.orthowatch.dto.whatsapp.WhatsAppMessageResponse;
import com.orthowatch.exception.WhatsAppApiException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class WhatsAppApiClient {

  private final WhatsAppProperties properties;
  private final RestTemplate restTemplate;

  private static final int MAX_RETRIES = 3;
  private static final long RETRY_DELAY_MS = 1000;

  public WhatsAppApiClient(WhatsAppProperties properties, RestTemplateBuilder restTemplateBuilder) {
    this.properties = properties;
    this.restTemplate = restTemplateBuilder.build();
  }

  public WhatsAppMessageResponse sendTextMessage(String to, String body) {
    WhatsAppMessageRequest request =
        WhatsAppMessageRequest.builder()
            .to(to)
            .type("text")
            .text(WhatsAppMessageRequest.TextBody.builder().body(body).build())
            .build();

    return sendMessage(request);
  }

  public WhatsAppMessageResponse sendTemplateMessage(
      String to,
      String templateName,
      String languageCode,
      List<String> parameterValues) {

    List<WhatsAppMessageRequest.TemplateBody.Component.Parameter> params =
        parameterValues.stream()
            .map(
                val ->
                    WhatsAppMessageRequest.TemplateBody.Component.Parameter.builder()
                        .type("text")
                        .text(val)
                        .build())
            .collect(Collectors.toList());

    WhatsAppMessageRequest.TemplateBody.Component bodyComponent =
        WhatsAppMessageRequest.TemplateBody.Component.builder()
            .type("body")
            .parameters(params)
            .build();

    WhatsAppMessageRequest request =
        WhatsAppMessageRequest.builder()
            .to(to)
            .type("template")
            .template(
                WhatsAppMessageRequest.TemplateBody.builder()
                    .name(templateName)
                    .language(
                        WhatsAppMessageRequest.TemplateBody.Language.builder()
                            .code(languageCode)
                            .build())
                    .components(List.of(bodyComponent))
                    .build())
            .build();

    return sendMessage(request);
  }

  public WhatsAppMessageResponse sendInteractiveButtonMessage(
      String to, String bodyText, List<ButtonOption> buttons) {

    List<WhatsAppMessageRequest.InteractiveBody.InteractiveAction.Button> buttonList =
        buttons.stream()
            .map(
                opt ->
                    WhatsAppMessageRequest.InteractiveBody.InteractiveAction.Button.builder()
                        .type("reply")
                        .reply(
                            WhatsAppMessageRequest.InteractiveBody.InteractiveAction.Button
                                .ButtonReply.builder()
                                .id(opt.getId())
                                .title(opt.getTitle())
                                .build())
                        .build())
            .collect(Collectors.toList());

    WhatsAppMessageRequest request =
        WhatsAppMessageRequest.builder()
            .to(to)
            .type("interactive")
            .interactive(
                WhatsAppMessageRequest.InteractiveBody.builder()
                    .type("button")
                    .body(
                        WhatsAppMessageRequest.InteractiveBody.InteractiveContent.builder()
                            .text(bodyText)
                            .build())
                    .action(
                        WhatsAppMessageRequest.InteractiveBody.InteractiveAction.builder()
                            .buttons(buttonList)
                            .build())
                    .build())
            .build();

    return sendMessage(request);
  }

  public WhatsAppMessageResponse sendMessage(WhatsAppMessageRequest request) {
    String url =
        properties.getApiUrl() + "/" + properties.getPhoneNumberId() + "/messages";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(properties.getAccessToken());

    HttpEntity<WhatsAppMessageRequest> entity = new HttpEntity<>(request, headers);

    int attempt = 0;
    while (true) {
      attempt++;
      try {
        ResponseEntity<WhatsAppMessageResponse> response =
            restTemplate.exchange(url, HttpMethod.POST, entity, WhatsAppMessageResponse.class);

        log.info(
            "WhatsApp message sent successfully to {} (type: {})",
            request.getTo(),
            request.getType());

        return response.getBody();
      } catch (HttpClientErrorException e) {
        log.error(
            "WhatsApp API client error ({}): {} - {}",
            e.getStatusCode(),
            e.getResponseBodyAsString(),
            request.getTo());
        throw new WhatsAppApiException(
            "WhatsApp API error (" + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
      } catch (RestClientException e) {
        if (attempt >= MAX_RETRIES) {
          log.error(
              "WhatsApp API failed after {} retries for {}: {}",
              MAX_RETRIES,
              request.getTo(),
              e.getMessage());
          throw new WhatsAppApiException(
              "WhatsApp API failed after " + MAX_RETRIES + " retries", e);
        }
        log.warn(
            "WhatsApp API attempt {}/{} failed for {}: {}. Retrying...",
            attempt,
            MAX_RETRIES,
            request.getTo(),
            e.getMessage());
        try {
          Thread.sleep(RETRY_DELAY_MS * attempt);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new WhatsAppApiException("Retry interrupted", ie);
        }
      }
    }
  }

  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  public static class ButtonOption {
    private String id;
    private String title;
  }
}
