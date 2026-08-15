package kz.hrms.splitupauth.entity;

public enum PaymentIntentStatus {
  PENDING,
  UNKNOWN,
  RECONCILING,
  SUCCESS,
  EXPIRED,
  REFUND_REQUIRED,
  REFUND_PENDING,
  REFUNDED,
  REQUIRES_REVIEW,
  CAPTURE_ANOMALY,
  FAILED,
  CANCELLED
}
