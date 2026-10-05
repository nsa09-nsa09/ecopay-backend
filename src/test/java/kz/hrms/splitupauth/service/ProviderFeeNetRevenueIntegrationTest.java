package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AdminDashboardKpisDto;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.JoinRoomRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Block 5d: a successful charge records the provider's acquiring fee on its transaction, and the
 * admin dashboard exposes net revenue = commission − provider fee (additive; identity always holds).
 */
class ProviderFeeNetRevenueIntegrationTest extends AbstractIntegrationTest {

  @Autowired AuthService authService;
  @Autowired PhoneVerificationService phoneVerificationService;
  @Autowired RoomService roomService;
  @Autowired RoomMemberService roomMemberService;
  @Autowired PaymentService paymentService;
  @Autowired AdminDashboardService adminDashboardService;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @BeforeEach
  void allowLegacyFourSeatTariff() {
    jdbcTemplate.update("UPDATE room_settings SET minimum_room_members = 4 WHERE id = 1");
  }

  @AfterEach
  void restoreRoomMinimum() {
    jdbcTemplate.update("UPDATE room_settings SET minimum_room_members = 5 WHERE id = 1");
  }

  @Test
  void successfulCharge_recordsProviderFee_andDashboardNetRevenueIsCommissionMinusFee() {
    User owner = registerVerified("Fee Owner");
    givePayoutCard(owner);
    User member = registerVerified("Fee Member");

    CreateRoomRequest create = new CreateRoomRequest();
    create.setServiceId(2L);
    create.setTariffPlanId(2L);
    create.setCategoryId(1L);
    create.setRoomType(RoomType.DIGITAL);
    create.setTitle("Fee Room");
    create.setStartDate(LocalDateTime.now().plusMonths(2));
    RoomResponse room = roomService.createRoom(owner, create);

    JoinRoomRequest join = new JoinRoomRequest();
    join.setConsentAccepted(true);
    join.setIdentifierValue("fee-member@gmail.com");
    RoomMemberDto membership = roomMemberService.joinRoom(room.getId(), member, join);

    CreatePaymentIntentRequest pay = new CreatePaymentIntentRequest();
    pay.setIdempotencyKey("fee-" + membership.getId());
    PaymentIntentResponse intent =
        paymentService.createPaymentIntent(membership.getId(), member, pay);
    assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());

    // amount = 2322.50; mock acquiring fee = 2.9% = 67.35 (HALF_UP).
    BigDecimal fee =
        jdbcTemplate.queryForObject(
            "SELECT provider_fee_amount FROM payment_transactions "
                + "WHERE payment_intent_id = ? AND type = 'CHARGE' AND status = 'SUCCESS'",
            BigDecimal.class,
            intent.getId());
    assertNotNull(fee, "successful charge must record the provider fee");
    assertEquals(0, new BigDecimal("67.35").compareTo(fee), "expected 2.9% of 2322.50");

    AdminDashboardKpisDto kpis = adminDashboardService.getKpis();
    assertNotNull(kpis.getProviderFeeTotal());
    assertNotNull(kpis.getNetRevenue());
    assertTrue(
        kpis.getProviderFeeTotal().signum() > 0, "provider fee total should include this charge");
    // Identity: netRevenue == platformRevenue − providerFeeTotal, regardless of other rows.
    assertEquals(
        0,
        kpis.getPlatformRevenue().subtract(kpis.getProviderFeeTotal()).compareTo(kpis.getNetRevenue()));
    // And the fee genuinely reduces margin.
    assertTrue(kpis.getNetRevenue().compareTo(kpis.getPlatformRevenue()) < 0);
  }

  private User registerVerified(String name) {
    int n = SEQ.incrementAndGet();
    RegisterRequest req = new RegisterRequest();
    req.setEmail("fee_" + n + "_" + System.nanoTime() + "@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName(name);
    authService.register(req, MailLocale.RU, null);
    User user = userRepository.findByEmail(req.getEmail()).orElseThrow();
    String phone = "+77" + String.format("%09d", (System.nanoTime() % 1_000_000_000L));
    phoneVerificationService.requestCode(user, phone, null);
    phoneVerificationService.verifyCode(user, phone, "000000");
    return userRepository.findByEmail(req.getEmail()).orElseThrow();
  }

  private void givePayoutCard(User owner) {
    jdbcTemplate.update(
        "INSERT INTO payout_methods (user_id, provider_name, provider_card_token, pan_mask, "
            + "is_default, status, created_at) VALUES (?, 'freedompay', ?, '4242', TRUE, "
            + "'ACTIVE', CURRENT_TIMESTAMP)",
        owner.getId(),
        "tok_test_" + owner.getId());
  }
}
