package com.orthowatch.exception;

/** Thrown when an uploaded file fails validation (wrong content type or exceeds size limit). */
public class InvalidFileException extends RuntimeException {
  public InvalidFileException(String message) {
    super(message);
  }
}
