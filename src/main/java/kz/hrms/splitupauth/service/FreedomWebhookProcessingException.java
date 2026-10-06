package kz.hrms.splitupauth.service;

import lombok.Getter;

@Getter
public class FreedomWebhookProcessingException extends RuntimeException {

  private final String errorCode;
  private final boolean retryable;

  public FreedomWebhookProcessingException(String errorCode, String message, boolean retryable) {
    super(message);
    this.errorCode = errorCode;
    this.retryable = retryable;
  }
}
