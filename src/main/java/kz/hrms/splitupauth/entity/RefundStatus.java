package kz.hrms.splitupauth.entity;

public enum RefundStatus {
  PENDING,
  PENDING_PROVIDER,
  SUCCESS,
  FAILED,
  REQUIRES_REVIEW,
  // B5: automatic refund dispatch is switched off (app.money.refund-dispatch-enabled=false). The
  // refund is recorded but will be sent by an operator through the FreedomPay cabinet by hand.
  PENDING_MANUAL,
  // B5: an operator confirmed they refunded this one manually; money moved outside the auto flow.
  SUCCESS_MANUAL
}
