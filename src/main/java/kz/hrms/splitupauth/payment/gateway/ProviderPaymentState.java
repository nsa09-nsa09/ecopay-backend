package kz.hrms.splitupauth.payment.gateway;

/**
 * Provider-side (card/acquirer) state of a payment, as reported by a status query or callback.
 *
 * <p>This is deliberately separate from EcoPay's own settlement concepts: AUTHORIZED/CAPTURED
 * describe money on the payer's card at the provider, while the owner payout hold/reserve is an
 * EcoPay-internal state of the owner's share after capture.
 */
public enum ProviderPaymentState {
  /** Created or awaiting the payer / payment system; no money moved yet. */
  PENDING,
  /** Two-step: amount held on the card but not cleared (pg_captured=0). Not captured money. */
  AUTHORIZED,
  /** Money captured (one-step success, or cleared two-step). */
  CAPTURED,
  FAILED,
  /** Payment page lifetime elapsed without payment. */
  EXPIRED,
  PARTIALLY_REFUNDED,
  REFUNDED,
  /** Provider answered with something EcoPay cannot classify; needs reconciliation or review. */
  UNKNOWN
}
