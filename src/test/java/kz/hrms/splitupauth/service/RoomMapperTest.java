package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceAccessType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.entity.VerificationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RoomMapperTest {

  private RoomMapper mapper;

  @BeforeEach
  void setUp() {
    CommissionCalculator commissionCalculator = new CommissionCalculator();
    ReflectionTestUtils.setField(commissionCalculator, "tier1Max", new BigDecimal("4000"));
    ReflectionTestUtils.setField(commissionCalculator, "tier2Max", new BigDecimal("6000"));
    ReflectionTestUtils.setField(commissionCalculator, "tier3Max", new BigDecimal("8000"));
    ReflectionTestUtils.setField(commissionCalculator, "tier1Fee", new BigDecimal("500"));
    ReflectionTestUtils.setField(commissionCalculator, "tier2Fee", new BigDecimal("700"));
    ReflectionTestUtils.setField(commissionCalculator, "tier3Fee", new BigDecimal("900"));
    ReflectionTestUtils.setField(commissionCalculator, "tier4Fee", new BigDecimal("1000"));

    mapper = new RoomMapper();
    ReflectionTestUtils.setField(mapper, "commissionCalculator", commissionCalculator);
  }

  @Test
  void toResponseExposesPaymentBreakdownForMemberCheckout() {
    Room room =
        Room.builder()
            .id(1191541224531623937L)
            .owner(user())
            .service(service())
            .roomType(RoomType.TELECOM)
            .verificationMode(VerificationMode.RISK_BASED)
            .status(RoomStatus.OPEN)
            .title("Beeline Family")
            .maxMembers(5)
            .priceTotal(new BigDecimal("6500.00"))
            .pricePerMember(new BigDecimal("1300.00"))
            .currency("KZT")
            .fxRateToKzt(new BigDecimal("1.000000"))
            .priceTotalKzt(new BigDecimal("6500.00"))
            .pricePerMemberKzt(new BigDecimal("1300.00"))
            .periodType(PeriodType.MONTHLY)
            .startDate(LocalDateTime.now().plusDays(1))
            .operatorTermsConfirmed(true)
            .createdAt(LocalDateTime.now())
            .build();

    RoomResponse response = mapper.toResponse(room);

    assertEquals(0, new BigDecimal("1300.00").compareTo(response.getShareKzt()));
    assertEquals(0, new BigDecimal("500.00").compareTo(response.getCommissionKzt()));
    assertEquals(0, new BigDecimal("1800.00").compareTo(response.getPayableTotalKzt()));
    assertEquals("KZT", response.getSettlementCurrency());
  }

  private User user() {
    return User.builder()
        .id(1L)
        .email("owner@example.com")
        .password("secret")
        .role(Role.USER)
        .displayName("Owner")
        .status(UserStatus.ACTIVE)
        .reputation(User.DEFAULT_REPUTATION)
        .build();
  }

  private ServiceEntity service() {
    return ServiceEntity.builder()
        .id(5L)
        .name("Beeline")
        .slug("beeline")
        .providerType(ProviderType.OPERATOR)
        .accessType(ServiceAccessType.PHONE)
        .isActive(true)
        .build();
  }
}
