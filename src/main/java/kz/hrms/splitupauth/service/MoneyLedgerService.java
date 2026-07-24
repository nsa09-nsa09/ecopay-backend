package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import kz.hrms.splitupauth.entity.MoneyLedgerEntry;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.MoneyLedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MoneyLedgerService {

  private final MoneyLedgerEntryRepository ledgerRepository;

  @Transactional
  public MoneyLedgerEntry append(
      String entryType,
      BigDecimal amount,
      String currency,
      String direction,
      PaymentIntent paymentIntent,
      PaymentTransaction paymentTransaction,
      RefundTransaction refundTransaction,
      Payout payout,
      User owner,
      String idempotencyKey) {
    var existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    if (amount == null || amount.signum() <= 0) {
      throw new InvalidRequestException("Ledger amount must be greater than zero");
    }
    return ledgerRepository.save(
        MoneyLedgerEntry.builder()
            .entryType(entryType)
            .amount(amount)
            .currency(currency == null ? "KZT" : currency)
            .direction(direction)
            .paymentIntent(paymentIntent)
            .paymentTransaction(paymentTransaction)
            .refundTransaction(refundTransaction)
            .payout(payout)
            .owner(owner)
            .idempotencyKey(idempotencyKey)
            .build());
  }
}
