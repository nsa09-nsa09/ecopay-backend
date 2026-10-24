package kz.hrms.splitupauth.payment.fiscal;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds fiscal receipt positions ({@code pg_receipt_positions[]}) for FreedomPay's OFD
 * integration. Shared by both pay-in (charge) and pay-out (refund) flows so that receipt line names
 * and tax types stay consistent across the charge and its reversal.
 */
@Component
@RequiredArgsConstructor
public class FiscalReceiptBuilder {

  private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final FiscalProperties properties;

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  /**
   * Receipt positions for a charge (initial payment or renewal). The sum of all positions must
   * exactly equal the total charge amount; a mismatch is a fatal configuration error.
   */
  public List<ReceiptPosition> forCharge(PaymentIntent intent) {
    BigDecimal share = shareFromIntent(intent);
    BigDecimal commission =
        intent.getCommissionAmount() == null ? BigDecimal.ZERO : intent.getCommissionAmount();
    BigDecimal total = intent.getAmount();

    String shareName = buildShareName(intent);
    List<ReceiptPosition> positions = new ArrayList<>(2);
    positions.add(
        new ReceiptPosition(shareName, BigDecimal.ONE, share, properties.getShareTaxType()));

    if (commission.signum() > 0) {
      positions.add(
          new ReceiptPosition(
              properties.getCommissionName(),
              BigDecimal.ONE,
              commission,
              properties.getCommissionTaxType()));
    }

    BigDecimal positionSum =
        positions.stream().map(ReceiptPosition::price).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (positionSum.compareTo(total) != 0) {
      throw new FiscalMismatchException(
          "Receipt position sum "
              + positionSum.toPlainString()
              + " does not match charge amount "
              + total.toPlainString());
    }
    return positions;
  }

  /**
   * Receipt positions for a refund. Stream B calls this when building the refund's {@code
   * pg_receipt_positions[]}. Both amounts are provided by the refund flow; their sum should equal
   * the refund amount.
   */
  public List<ReceiptPosition> forRefund(
      PaymentTransaction chargeTx, BigDecimal shareRefund, BigDecimal commissionRefund) {
    String shareName = buildShareNameFromTx(chargeTx);
    List<ReceiptPosition> positions = new ArrayList<>(2);
    if (shareRefund != null && shareRefund.signum() > 0) {
      positions.add(
          new ReceiptPosition(
              shareName, BigDecimal.ONE, shareRefund, properties.getShareTaxType()));
    }
    if (commissionRefund != null && commissionRefund.signum() > 0) {
      positions.add(
          new ReceiptPosition(
              properties.getCommissionName(),
              BigDecimal.ONE,
              commissionRefund,
              properties.getCommissionTaxType()));
    }
    return positions;
  }

  private String buildShareName(PaymentIntent intent) {
    RoomMember member = intent.getRoomMember();
    Room room = member == null ? null : member.getRoom();
    String serviceName = resolveServiceName(room);
    String tariffName = resolveTariffName(room);
    String period = resolvePeriod(intent, room);
    return properties
        .getShareNameTemplate()
        .replace("{service}", serviceName)
        .replace("{tariff}", tariffName)
        .replace("{period}", period);
  }

  private static BigDecimal shareFromIntent(PaymentIntent intent) {
    BigDecimal commission =
        intent.getCommissionAmount() == null ? BigDecimal.ZERO : intent.getCommissionAmount();
    return intent.getAmount().subtract(commission);
  }

  private String buildShareNameFromTx(PaymentTransaction tx) {
    Room room = tx.getRoom();
    String serviceName = resolveServiceName(room);
    String tariffName = resolveTariffName(room);
    return properties
        .getShareNameTemplate()
        .replace("{service}", serviceName)
        .replace("{tariff}", tariffName)
        .replace("{period}", "возврат");
  }

  private static String resolveServiceName(Room room) {
    if (room == null) return "EcoPay";
    ServiceEntity service = room.getService();
    return service != null && service.getName() != null ? service.getName() : "EcoPay";
  }

  private static String resolveTariffName(Room room) {
    if (room == null) return "подписка";
    TariffPlan tariff = room.getTariffPlan();
    return tariff != null && tariff.getName() != null ? tariff.getName() : "подписка";
  }

  private static String resolvePeriod(PaymentIntent intent, Room room) {
    if (intent.getBillingPeriodStart() != null && room != null && room.getStartDate() != null) {
      return intent.getBillingPeriodStart().format(PERIOD_FMT)
          + "–"
          + intent.getBillingPeriodStart().plusMonths(1).format(PERIOD_FMT);
    }
    if (room != null && room.getStartDate() != null) {
      return room.getStartDate().format(PERIOD_FMT) + "–…";
    }
    return "";
  }

  public record ReceiptPosition(String name, BigDecimal count, BigDecimal price, int taxType) {}
}
