package kz.hrms.splitupauth.payment.fiscal;

public class FiscalMismatchException extends RuntimeException {

  public FiscalMismatchException(String message) {
    super(message);
  }
}
