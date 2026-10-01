package kz.hrms.splitupauth.service;

import java.util.List;

final class PayoutHoldPolicy {

  static final String CURRENCY = "KZT";
  static final List<String> HELD_STATUSES = List.of("PENDING", "PENDING_METHOD", "FROZEN");

  private PayoutHoldPolicy() {}
}
