package kz.hrms.splitupauth.payment.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import org.junit.jupiter.api.Test;

class FiscalReceiptBuilderTest {

  @Test
  void forCharge_twoPositions_sumEqualsAmount() {
    FiscalReceiptBuilder builder = builder(true, 7, 3);

    PaymentIntent intent = intentWithCommission("1000.00", "150.00");
    List<FiscalReceiptBuilder.ReceiptPosition> positions = builder.forCharge(intent);

    assertEquals(2, positions.size());
    assertEquals(new BigDecimal("850.00"), positions.get(0).price());
    assertEquals(7, positions.get(0).taxType());
    assertEquals(new BigDecimal("150.00"), positions.get(1).price());
    assertEquals(3, positions.get(1).taxType());
    BigDecimal sum =
        positions.stream()
            .map(FiscalReceiptBuilder.ReceiptPosition::price)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, sum.compareTo(intent.getAmount()));
  }

  @Test
  void forCharge_zeroCommission_singlePosition() {
    FiscalReceiptBuilder builder = builder(true, 0, 0);

    PaymentIntent intent = intentWithCommission("500.00", "0.00");
    List<FiscalReceiptBuilder.ReceiptPosition> positions = builder.forCharge(intent);

    assertEquals(1, positions.size());
    assertEquals(new BigDecimal("500.00"), positions.get(0).price());
  }

  @Test
  void forCharge_nullCommission_treatedAsZero() {
    FiscalReceiptBuilder builder = builder(true, 0, 0);

    PaymentIntent intent = intentWithCommission("300.00", null);
    List<FiscalReceiptBuilder.ReceiptPosition> positions = builder.forCharge(intent);

    assertEquals(1, positions.size());
    assertEquals(new BigDecimal("300.00"), positions.get(0).price());
  }

  @Test
  void forRefund_twoPositions() {
    FiscalReceiptBuilder builder = builder(true, 7, 3);

    PaymentTransaction tx = new PaymentTransaction();
    Room room = new Room();
    tx.setRoom(room);

    List<FiscalReceiptBuilder.ReceiptPosition> positions =
        builder.forRefund(tx, new BigDecimal("400.00"), new BigDecimal("50.00"));

    assertEquals(2, positions.size());
    assertEquals(new BigDecimal("400.00"), positions.get(0).price());
    assertEquals(7, positions.get(0).taxType());
    assertEquals(new BigDecimal("50.00"), positions.get(1).price());
    assertEquals(3, positions.get(1).taxType());
  }

  @Test
  void forRefund_zeroCommission_singlePosition() {
    FiscalReceiptBuilder builder = builder(true, 0, 0);

    PaymentTransaction tx = new PaymentTransaction();
    Room room = new Room();
    tx.setRoom(room);

    List<FiscalReceiptBuilder.ReceiptPosition> positions =
        builder.forRefund(tx, new BigDecimal("200.00"), BigDecimal.ZERO);

    assertEquals(1, positions.size());
  }

  @Test
  void isEnabled_reflectsProperties() {
    assertFalse(builder(false, 0, 0).isEnabled());
    assertTrue(builder(true, 0, 0).isEnabled());
  }

  @Test
  void forCharge_shareNameIncludesServiceAndTariff() {
    FiscalReceiptBuilder builder = builder(true, 0, 0);

    PaymentIntent intent = new PaymentIntent();
    intent.setAmount(new BigDecimal("500.00"));
    intent.setCommissionAmount(BigDecimal.ZERO);
    RoomMember member = new RoomMember();
    Room room = new Room();
    ServiceEntity service = new ServiceEntity();
    service.setName("Netflix");
    room.setService(service);
    TariffPlan tariff = new TariffPlan();
    tariff.setName("Premium");
    room.setTariffPlan(tariff);
    member.setRoom(room);
    intent.setRoomMember(member);

    List<FiscalReceiptBuilder.ReceiptPosition> positions = builder.forCharge(intent);
    assertTrue(positions.get(0).name().contains("Netflix"));
    assertTrue(positions.get(0).name().contains("Premium"));
  }

  private static FiscalReceiptBuilder builder(boolean enabled, int shareTax, int commissionTax) {
    FiscalProperties props = new FiscalProperties();
    props.setEnabled(enabled);
    props.setShareTaxType(shareTax);
    props.setCommissionTaxType(commissionTax);
    return new FiscalReceiptBuilder(props);
  }

  private static PaymentIntent intentWithCommission(String amount, String commission) {
    PaymentIntent intent = new PaymentIntent();
    intent.setAmount(new BigDecimal(amount));
    intent.setCommissionAmount(commission != null ? new BigDecimal(commission) : null);
    RoomMember member = new RoomMember();
    Room room = new Room();
    member.setRoom(room);
    intent.setRoomMember(member);
    return intent;
  }
}
