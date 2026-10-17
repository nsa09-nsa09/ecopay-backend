package kz.hrms.splitupauth.payment.gateway;

/**
 * The request provably never reached the provider (local concurrency limit, connection refused or
 * connect timeout). Unlike a read timeout this is NOT ambiguous: no financial operation can exist
 * at the provider, so a money-moving call may be safely retried later with the same idempotency key
 * / order id.
 */
public class GatewayRequestNotSentException extends RuntimeException {
  public GatewayRequestNotSentException(String message, Throwable cause) {
    super(message, cause);
  }
}
