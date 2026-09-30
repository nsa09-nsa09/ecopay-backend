package kz.hrms.splitupauth.entity;

/**
 * Why a payment intent exists. The capture handler needs it to tell a
 * legitimate renewal of an ACTIVE membership from a duplicate capture of the
 * joining payment.
 */
public enum PaymentPurpose {
    /** Buys the seat: membership APPLIED -> PENDING. */
    INITIAL,
    /** Renews an ACTIVE membership for the next period. */
    RECURRING
}
