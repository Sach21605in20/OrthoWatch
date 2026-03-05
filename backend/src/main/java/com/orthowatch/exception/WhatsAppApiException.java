package com.orthowatch.exception;

public class WhatsAppApiException extends RuntimeException {

  public WhatsAppApiException(String message) {
    super(message);
  }

  public WhatsAppApiException(String message, Throwable cause) {
    super(message, cause);
  }
}
