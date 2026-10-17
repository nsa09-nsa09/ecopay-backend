package kz.hrms.splitupauth.entity;

/**
 * What a {@link PaymentIntent} is paying for.
 *
 * <ul>
 *   <li>{@code INITIAL} — the first payment that turns an APPLIED membership into a paid one.
 *   <li>{@code RENEWAL} — a member-initiated manual payment for the next billing period (hosted
 *       FreedomPay page, no stored card), the MVP's way to keep a membership paid.
 *   <li>{@code RECURRING} — an automatic saved-card charge (auto-renewal; disabled in production).
 * </ul>
 */
public enum PaymentIntentPurpose {
  INITIAL,
  RENEWAL,
  RECURRING
}
