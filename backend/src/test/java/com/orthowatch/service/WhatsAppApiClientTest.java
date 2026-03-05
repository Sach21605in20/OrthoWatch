package com.orthowatch.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.orthowatch.config.WhatsAppProperties;
import com.orthowatch.dto.whatsapp.WhatsAppMessageRequest;
import com.orthowatch.dto.whatsapp.WhatsAppMessageResponse;
import com.orthowatch.exception.WhatsAppApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class WhatsAppApiClientTest {

  @Mock private RestTemplate restTemplate;

  private WhatsAppApiClient apiClient;
  private WhatsAppProperties properties;

  @BeforeEach
  void setUp() {
    properties = new WhatsAppProperties();
    properties.setApiUrl("https://graph.facebook.com/v18.0");
    properties.setPhoneNumberId("123456789");
    properties.setAccessToken("test-access-token");
    properties.setVerifyToken("test-verify-token");

    RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
    when(builder.build()).thenReturn(restTemplate);

    apiClient = new WhatsAppApiClient(properties, builder);
  }

  @Test
  void sendTextMessage_shouldCallRestTemplateWithCorrectPayload() {
    WhatsAppMessageResponse mockResponse =
        WhatsAppMessageResponse.builder()
            .messages(
                List.of(WhatsAppMessageResponse.Message.builder().id("msg-123").build()))
            .build();

    when(restTemplate.exchange(
            anyString(),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(WhatsAppMessageResponse.class)))
        .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

    WhatsAppMessageResponse result = apiClient.sendTextMessage("+919876543210", "Hello");

    assertNotNull(result);
    assertEquals("msg-123", result.getMessages().get(0).getId());

    ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            eq("https://graph.facebook.com/v18.0/123456789/messages"),
            eq(HttpMethod.POST),
            entityCaptor.capture(),
            eq(WhatsAppMessageResponse.class));

    HttpEntity<WhatsAppMessageRequest> capturedEntity = entityCaptor.getValue();
    assertEquals(
        MediaType.APPLICATION_JSON, capturedEntity.getHeaders().getContentType());
    assertTrue(
        capturedEntity
            .getHeaders()
            .get("Authorization")
            .get(0)
            .contains("test-access-token"));

    WhatsAppMessageRequest body = (WhatsAppMessageRequest) capturedEntity.getBody();
    assertEquals("+919876543210", body.getTo());
    assertEquals("text", body.getType());
    assertEquals("Hello", body.getText().getBody());
  }

  @Test
  void sendTemplateMessage_shouldBuildCorrectPayloadStructure() {
    WhatsAppMessageResponse mockResponse =
        WhatsAppMessageResponse.builder()
            .messages(
                List.of(WhatsAppMessageResponse.Message.builder().id("msg-456").build()))
            .build();

    when(restTemplate.exchange(
            anyString(),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(WhatsAppMessageResponse.class)))
        .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

    WhatsAppMessageResponse result =
        apiClient.sendTemplateMessage(
            "+919876543210",
            "consent_request",
            "en",
            List.of("John Doe", "TKR"));

    assertNotNull(result);

    ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), any(Class.class));

    WhatsAppMessageRequest body = (WhatsAppMessageRequest) entityCaptor.getValue().getBody();
    assertEquals("template", body.getType());
    assertEquals("consent_request", body.getTemplate().getName());
    assertEquals("en", body.getTemplate().getLanguage().getCode());
    assertEquals(2, body.getTemplate().getComponents().get(0).getParameters().size());
  }

  @Test
  void sendInteractiveButtonMessage_shouldBuildCorrectButtonStructure() {
    WhatsAppMessageResponse mockResponse =
        WhatsAppMessageResponse.builder()
            .messages(
                List.of(WhatsAppMessageResponse.Message.builder().id("msg-789").build()))
            .build();

    when(restTemplate.exchange(
            anyString(),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(WhatsAppMessageResponse.class)))
        .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

    List<WhatsAppApiClient.ButtonOption> buttons =
        List.of(
            WhatsAppApiClient.ButtonOption.builder().id("pain_low").title("0-3 Low").build(),
            WhatsAppApiClient.ButtonOption.builder()
                .id("pain_moderate")
                .title("4-6 Moderate")
                .build(),
            WhatsAppApiClient.ButtonOption.builder()
                .id("pain_severe")
                .title("7-10 Severe")
                .build());

    WhatsAppMessageResponse result =
        apiClient.sendInteractiveButtonMessage("+919876543210", "Rate your pain", buttons);

    assertNotNull(result);

    ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), any(Class.class));

    WhatsAppMessageRequest body = (WhatsAppMessageRequest) entityCaptor.getValue().getBody();
    assertEquals("interactive", body.getType());
    assertEquals("button", body.getInteractive().getType());
    assertEquals("Rate your pain", body.getInteractive().getBody().getText());
    assertEquals(3, body.getInteractive().getAction().getButtons().size());
    assertEquals(
        "pain_low",
        body.getInteractive().getAction().getButtons().get(0).getReply().getId());
  }

  @Test
  void sendMessage_shouldThrowWhatsAppApiExceptionOnClientError() {
    when(restTemplate.exchange(
            anyString(),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(WhatsAppMessageResponse.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null));

    WhatsAppApiException exception =
        assertThrows(
            WhatsAppApiException.class,
            () -> apiClient.sendTextMessage("+919876543210", "Hello"));

    assertTrue(exception.getMessage().contains("WhatsApp API error"));
    // Client errors should NOT retry — only 1 call
    verify(restTemplate, times(1))
        .exchange(anyString(), any(), any(HttpEntity.class), eq(WhatsAppMessageResponse.class));
  }

  @Test
  void sendMessage_shouldRetryOnServerErrorAndEventuallyThrow() {
    when(restTemplate.exchange(
            anyString(),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(WhatsAppMessageResponse.class)))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Server Error",
                HttpHeaders.EMPTY,
                new byte[0],
                null));

    WhatsAppApiException exception =
        assertThrows(
            WhatsAppApiException.class,
            () -> apiClient.sendTextMessage("+919876543210", "Hello"));

    assertTrue(exception.getMessage().contains("failed after 3 retries"));
    // Server errors SHOULD retry 3 times
    verify(restTemplate, times(3))
        .exchange(anyString(), any(), any(HttpEntity.class), eq(WhatsAppMessageResponse.class));
  }
}
