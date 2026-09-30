package kz.hrms.splitupauth.finance;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.JoinRoomRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.MockPaymentGateway;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.service.AuthService;
import kz.hrms.splitupauth.service.PaymentService;
import kz.hrms.splitupauth.service.PhoneVerificationService;
import kz.hrms.splitupauth.service.RoomMemberService;
import kz.hrms.splitupauth.service.RoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Shared fixtures for the money-path concurrency tests. Everything runs against
 * the real PostgreSQL container (no repository mocks) so row locks, unique
 * indexes, CHECK constraints and triggers are what decide the outcome.
 *
 * <p>The mock gateway is wrapped in a Mockito spy so tests can count provider
 * calls (the thing that must never be doubled) and inject provider behaviour
 * such as redirect-based charges or timeouts after acceptance.
 */
public abstract class FinancialTestSupport extends AbstractIntegrationTest {

    protected static final BigDecimal SEAT_PRICE = new BigDecimal("1000.00");

    @MockitoSpyBean protected MockPaymentGateway gateway;

    @Autowired protected AuthService authService;
    @Autowired protected PhoneVerificationService phoneVerificationService;
    @Autowired protected RoomService roomService;
    @Autowired protected RoomMemberService roomMemberService;
    @Autowired protected PaymentService paymentService;
    @Autowired protected UserRepository userRepository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ------------------------------------------------------------------ fixtures

    protected User registerVerified(String name) {
        int n = SEQ.incrementAndGet();
        RegisterRequest req = new RegisterRequest();
        req.setEmail("fin_" + n + "_" + System.nanoTime() + "@test.kz");
        req.setPassword("Test1234");
        req.setDisplayName(name);
        String phone = "+77" + String.format("%09d", Math.floorMod(System.nanoTime() + n * 7919L, 1_000_000_000L));
        req.setPhone(phone);
        authService.register(req);
        User user = userRepository.findByEmail(req.getEmail()).orElseThrow();
        phoneVerificationService.verifyCode(user, phone, "000000");
        return userRepository.findByEmail(req.getEmail()).orElseThrow();
    }

    protected User registerAdmin() {
        User u = registerVerified("Fin Admin");
        jdbc.update("update users set role = 'ADMIN' where id = ?", u.getId());
        return userRepository.findById(u.getId()).orElseThrow();
    }

    /** DIGITAL room; the owner holds one of {@code maxMembers} seats. */
    protected RoomResponse createRoom(User owner, int maxMembers) {
        CreateRoomRequest create = new CreateRoomRequest();
        create.setServiceId(2L);
        create.setTariffPlanId(2L);
        create.setCategoryId(1L);
        create.setRoomType(RoomType.DIGITAL);
        create.setTitle("Fin room " + UUID.randomUUID().toString().substring(0, 8));
        create.setMaxMembers(maxMembers);
        create.setPricePerMember(SEAT_PRICE);
        create.setCurrency("KZT");
        create.setPeriodType(PeriodType.MONTHLY);
        create.setStartDate(LocalDateTime.now().plusMonths(2));
        return roomService.createRoom(owner, create);
    }

    protected Long join(Long roomId, User user) {
        JoinRoomRequest join = new JoinRoomRequest();
        join.setConsentAccepted(true);
        return roomMemberService.joinRoom(roomId, user, join).getId();
    }

    protected PaymentIntentResponse pay(Long memberId, User user, String key) {
        CreatePaymentIntentRequest req = new CreatePaymentIntentRequest();
        req.setIdempotencyKey(key);
        return paymentService.createPaymentIntent(memberId, user, req);
    }

    /** Make the spy gateway answer charges like real Freedom Pay: redirect, capture later via webhook. */
    protected void gatewayRequiresRedirect() {
        doAnswer(inv -> GatewayChargeResponse.builder()
                .success(true)
                .requiresRedirect(true)
                .externalPaymentId("FP-" + UUID.randomUUID())
                .paymentUrl("https://pay.example/redirect")
                .providerStatusCode("ok")
                .build()).when(gateway).initCharge(any());
    }

    /** Registers an ACTIVE default payout method for the owner so the dispatcher can pay out. */
    protected void givePayoutMethod(User owner) {
        jdbc.update("""
                insert into payout_methods (user_id, provider_name, provider_card_token, pan_mask,
                                            is_default, status, created_at)
                values (?, 'freedompay', ?, '4400****0000', true, 'ACTIVE', now())
                """, owner.getId(), "tok-" + UUID.randomUUID());
    }

    // ------------------------------------------------------------------ queries

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    protected long occupiedSeats(Long roomId) {
        return count("select count(*) from room_members where room_id = ? and deleted_at is null "
                + "and status in ('PENDING','ACTIVE')", roomId);
    }

    protected long chargesInRoom(Long roomId) {
        return count("select count(*) from payment_transactions where room_id = ? and type = 'CHARGE'", roomId);
    }

    protected long payoutsInRoom(Long roomId) {
        return count("select count(*) from payouts where room_id = ?", roomId);
    }

    protected Long payoutIdForRoom(Long roomId) {
        return jdbc.queryForObject("select id from payouts where room_id = ? order by id limit 1", Long.class, roomId);
    }

    protected String payoutStatus(Long payoutId) {
        return jdbc.queryForObject("select status from payouts where id = ?", String.class, payoutId);
    }

    // ------------------------------------------------------------------ concurrency

    /** Result of one concurrent call: either a value or the exception it threw. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean ok() { return error == null; }
    }

    /**
     * Runs all tasks at the same instant (start gate) and waits for them. Every
     * task's value or exception is captured, never swallowed.
     */
    protected <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<T>(null, t);
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                outcomes.add(f.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Starts a task in the background and returns its future (used around held row locks). */
    protected <T> Future<Outcome<T>> startAsync(ExecutorService pool, Callable<T> task) {
        return pool.submit(() -> {
            try {
                return new Outcome<>(task.call(), null);
            } catch (Throwable t) {
                return new Outcome<T>(null, t);
            }
        });
    }

    /** Opens a raw connection and takes a row lock, simulating a slow concurrent writer. */
    protected Connection lockRow(String table, Long id) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (var st = c.prepareStatement("select id from " + table + " where id = ? for update")) {
            st.setLong(1, id);
            st.executeQuery();
        }
        return c;
    }

    /** Waits until at least {@code n} backends are blocked on a lock (or the timeout passes). */
    protected void awaitLockWaiters(int n, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long waiting = count("select count(*) from pg_stat_activity where datname = current_database() "
                    + "and wait_event_type = 'Lock'");
            if (waiting >= n) return;
            Thread.sleep(25);
        }
    }

    protected static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }
}
