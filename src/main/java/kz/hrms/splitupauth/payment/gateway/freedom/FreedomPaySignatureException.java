package kz.hrms.splitupauth.payment.gateway.freedom;

/**
 * The provider answered, but the answer is not provably from FreedomPay (missing or wrong {@code
 * pg_sig}). Callers must treat the operation as UNKNOWN: never as success, and for money-moving
 * calls never as a definite failure either, because the request may have been accepted.
 */
public class FreedomPaySignatureException extends FreedomPayException {
  public FreedomPaySignatureException(String message) {
    super(message);
  }
}
