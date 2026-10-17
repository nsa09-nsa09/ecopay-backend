package kz.hrms.splitupauth.service;

import java.util.List;
import kz.hrms.splitupauth.entity.PayoutStatus;

final class PayoutHoldPolicy {

  static final String CURRENCY = "KZT";
  static final List<PayoutStatus> HELD_STATUSES =
      List.of(PayoutStatus.PENDING, PayoutStatus.PENDING_METHOD, PayoutStatus.FROZEN);

  private PayoutHoldPolicy() {}
}
