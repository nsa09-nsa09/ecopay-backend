package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.entity.VerificationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RoomMapperSettlementTest {

  @Mock private CommissionCalculator commissionCalculator;

  private RoomMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new RoomMapper();
    ReflectionTestUtils.setField(mapper, "commissionCalculator", commissionCalculator);
  }

  @Test
  void usdRoomSettlementFieldsUseFrozenKztShare() {
    BigDecimal shareKzt = new BigDecimal("12300.00");
    when(commissionCalculator.commissionFor(new BigDecimal("25.00")))
        .thenReturn(new BigDecimal("500.00"));
    when(commissionCalculator.commissionFor(shareKzt)).thenReturn(new BigDecimal("1000.00"));

    RoomResponse response =
        mapper.toResponse(room("USD", new BigDecimal("25.00"), new BigDecimal("492.000000"), shareKzt));

    assertEquals(0, shareKzt.compareTo(response.getShareKzt()));
    assertEquals(0, new BigDecimal("1000.00").compareTo(response.getCommissionKzt()));
    assertEquals(0, new BigDecimal("13300.00").compareTo(response.getPayableTotalKzt()));
    assertEquals("KZT", response.getSettlementCurrency());
    assertEquals(0, new BigDecimal("492.000000").compareTo(response.getFxRateSnapshot()));
    assertEquals(0, new BigDecimal("25.00").compareTo(response.getOriginalTariffPrice()));
    assertEquals("USD", response.getOriginalTariffCurrency());
    verify(commissionCalculator).commissionFor(shareKzt);
  }

  @Test
  void eurRoomSettlementFieldsUseFrozenKztShare() {
    BigDecimal shareKzt = new BigDecimal("8200.00");
    when(commissionCalculator.commissionFor(new BigDecimal("15.00")))
        .thenReturn(new BigDecimal("500.00"));
    when(commissionCalculator.commissionFor(shareKzt)).thenReturn(new BigDecimal("1000.00"));

    RoomResponse response =
        mapper.toResponse(room("EUR", new BigDecimal("15.00"), new BigDecimal("546.666667"), shareKzt));

    assertEquals(0, shareKzt.compareTo(response.getShareKzt()));
    assertEquals(0, new BigDecimal("1000.00").compareTo(response.getCommissionKzt()));
    assertEquals(0, new BigDecimal("9200.00").compareTo(response.getPayableTotalKzt()));
    assertEquals("KZT", response.getSettlementCurrency());
    assertEquals(0, new BigDecimal("546.666667").compareTo(response.getFxRateSnapshot()));
    assertEquals(0, new BigDecimal("15.00").compareTo(response.getOriginalTariffPrice()));
    assertEquals("EUR", response.getOriginalTariffCurrency());
    verify(commissionCalculator).commissionFor(shareKzt);
  }

  private static Room room(
      String currency, BigDecimal pricePerMember, BigDecimal fxRateToKzt, BigDecimal shareKzt) {
    return Room.builder()
        .id(10L)
        .owner(owner())
        .service(service())
        .roomType(RoomType.DIGITAL)
        .verificationMode(VerificationMode.RISK_BASED)
        .status(RoomStatus.OPEN)
        .title("Foreign currency room")
        .maxMembers(4)
        .priceTotal(pricePerMember.multiply(new BigDecimal("4")))
        .pricePerMember(pricePerMember)
        .currency(currency)
        .fxRateToKzt(fxRateToKzt)
        .priceTotalKzt(shareKzt.multiply(new BigDecimal("4")))
        .pricePerMemberKzt(shareKzt)
        .periodType(PeriodType.MONTHLY)
        .operatorTermsConfirmed(false)
        .build();
  }

  private static User owner() {
    return User.builder()
        .id(1L)
        .publicId("owner-public")
        .email("owner@example.com")
        .displayName("Owner")
        .role(Role.USER)
        .status(UserStatus.ACTIVE)
        .ownerVerified(true)
        .reputation(0)
        .build();
  }

  private static ServiceEntity service() {
    return ServiceEntity.builder()
        .id(2L)
        .name("Service")
        .slug("service")
        .providerType(ProviderType.DIGITAL)
        .build();
  }
}
