package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.DashboardLabelValueDto;
import kz.hrms.splitupauth.dto.OperatorDistributionDto;
import kz.hrms.splitupauth.dto.PopularServiceDto;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.entity.VerificationMode;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for the admin dashboard chart aggregates. Exercises
 * each chart against a real Postgres so the SQL grammar (previous bug:
 * BadSqlGrammarException on operator/category charts) stays honest under
 * Postgres' parameter inference rules.
 */
class AdminDashboardChartsServiceTest extends AbstractIntegrationTest {

    @Autowired AdminDashboardChartsService chartsService;
    @Autowired UserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ServiceRepository serviceRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired RoomMemberRepository roomMemberRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ===================== empty-DB safety =====================

    @Test
    void allFiveCharts_returnEmptyListsWhenNoMatchingData_neverThrow() {
        // No setup data — chart methods must return [] (or near-empty) and
        // never bubble a SQL grammar error to the caller.
        assertNotNull(chartsService.popularServices(null));
        assertNotNull(chartsService.operatorDistribution());
        assertNotNull(chartsService.currencyDistribution());
        assertNotNull(chartsService.categoryDistribution());
        assertNotNull(chartsService.roomStatusDistribution());
    }

    // ===================== popular services =====================

    @Test
    void popularServices_ranksByRoomCountAndActiveMembers() {
        User owner = saveUser("popOwner");
        Category cat = saveCategory("popCat");
        ServiceEntity svcA = saveService(cat, "Popular A");
        ServiceEntity svcB = saveService(cat, "Niche B");

        Room a1 = saveRoom(owner, svcA, cat, "A1", RoomStatus.ACTIVE);
        Room a2 = saveRoom(owner, svcA, cat, "A2", RoomStatus.ACTIVE);
        Room b1 = saveRoom(owner, svcB, cat, "B1", RoomStatus.ACTIVE);

        saveMember(a1, saveUser("m1"), MemberStatus.ACTIVE);
        saveMember(a1, saveUser("m2"), MemberStatus.ACTIVE);
        saveMember(a2, saveUser("m3"), MemberStatus.ACTIVE);
        saveMember(b1, saveUser("m4"), MemberStatus.PENDING); // not counted (status != ACTIVE)

        // Use the max page size — limit=5 is fragile because sibling tests may
        // have created services with higher room counts that bump svcB out of
        // the top window.
        List<PopularServiceDto> ranked = chartsService.popularServices(100);

        // svcA has 2 rooms (vs 1 for svcB) → comes first regardless of where
        // svcB sits among any pre-existing fixtures.
        PopularServiceDto first = ranked.stream()
                .filter(p -> p.getServiceId().equals(svcA.getId())).findFirst().orElseThrow();
        PopularServiceDto second = ranked.stream()
                .filter(p -> p.getServiceId().equals(svcB.getId())).findFirst().orElseThrow();

        assertEquals(2, first.getRoomsCount());
        assertEquals(3, first.getActiveMembersCount(), "two members in A1 + one in A2");
        assertEquals(1, second.getRoomsCount());
        assertEquals(0, second.getActiveMembersCount(), "PENDING members don't count");
        assertTrue(ranked.indexOf(first) < ranked.indexOf(second));
    }

    @Test
    void popularServices_limitParameterIsHonored() {
        // 6 services with one room each; asking for limit=2 must return ≤2.
        Category cat = saveCategory("limCat");
        User owner = saveUser("limOwner");
        for (int i = 0; i < 6; i++) {
            ServiceEntity s = saveService(cat, "Lim" + i);
            saveRoom(owner, s, cat, "R" + i, RoomStatus.ACTIVE);
        }
        List<PopularServiceDto> two = chartsService.popularServices(2);
        assertEquals(2, two.size());
    }

    // ===================== operator distribution =====================

    @Test
    void operatorDistribution_groupsKzDefCodesAndOtherBucket() {
        // The aggregate scans the whole `users` table, so sibling tests (and
        // V7/V10 seed data) may have already contributed rows to each bucket.
        // Snapshot the baseline first, then assert the *delta* this test
        // produced — that keeps the assertion independent of unrelated data.
        List<OperatorDistributionDto> before = chartsService.operatorDistribution();
        long before701 = countFor(before, "701");
        long before707 = countFor(before, "707");
        long beforeOther = countFor(before, "OTHER");

        // +7 701 → Kcell-Activ; +7 707 → Tele2-Altel; +7 999 (unknown) → OTHER;
        // foreign-format number → OTHER.
        saveUserWithPhone("oi1", "+77011112233");
        saveUserWithPhone("oi2", "+77071234567");
        saveUserWithPhone("oi3", "+79991234567"); // unknown KZ code → OTHER
        saveUserWithPhone("oi4", "+14155550100"); // foreign → OTHER

        List<OperatorDistributionDto> dist = chartsService.operatorDistribution();
        assertNotNull(dist);

        long kcell = countFor(dist, "701");
        long tele2 = countFor(dist, "707");
        long other = countFor(dist, "OTHER");

        assertEquals(1, kcell - before701, "+7 701 must map to its own bucket (delta)");
        assertEquals(1, tele2 - before707, "+7 707 must map to its own bucket (delta)");
        assertTrue(other - beforeOther >= 2,
                "unknown KZ code + foreign number both land in OTHER (delta)");
    }

    // ===================== currency distribution =====================

    @Test
    void currencyDistribution_countsActiveRoomsByCurrency() {
        // Same baseline-delta pattern as operatorDistribution: this query
        // aggregates the global `rooms` table, so sibling-test rows leak in
        // and absolute counts cannot be asserted deterministically.
        List<DashboardLabelValueDto> before = chartsService.currencyDistribution();
        long beforeKzt = valueFor(before, "KZT");
        long beforeUsd = valueFor(before, "USD");

        User owner = saveUser("curOwner");
        Category cat = saveCategory("curCat");
        ServiceEntity svc = saveService(cat, "curSvc");

        saveRoomWithCurrency(owner, svc, cat, "kz1", "KZT", RoomStatus.ACTIVE);
        saveRoomWithCurrency(owner, svc, cat, "kz2", "KZT", RoomStatus.ACTIVE);
        saveRoomWithCurrency(owner, svc, cat, "usd1", "USD", RoomStatus.ACTIVE);
        saveRoomWithCurrency(owner, svc, cat, "comp", "USD", RoomStatus.COMPLETED); // not counted

        List<DashboardLabelValueDto> dist = chartsService.currencyDistribution();
        long kzt = valueFor(dist, "KZT");
        long usd = valueFor(dist, "USD");
        assertEquals(2, kzt - beforeKzt, "two ACTIVE KZT rooms added in this test");
        assertEquals(1, usd - beforeUsd, "one ACTIVE USD room added (COMPLETED excluded)");
    }

    // ===================== category distribution =====================

    @Test
    void categoryDistribution_putsRoomsWithoutCategoryUnderFallbackLabel() {
        // The "Без категории" bucket aggregates *all* category-less rooms across
        // the whole `rooms` table — sibling tests may have left some behind.
        // Snapshot before insert so we can assert our own contribution exactly.
        List<DashboardLabelValueDto> before = chartsService.categoryDistribution();
        long beforeWithoutCat = valueFor(before, "Без категории");

        User owner = saveUser("catOwner");
        Category cat = saveCategory("catA");
        ServiceEntity svc = saveService(cat, "catSvc");

        saveRoom(owner, svc, cat, "withCat1", RoomStatus.OPEN);
        // Room without category — uses LEFT JOIN; should bucket under fallback.
        saveRoomNoCategory(owner, svc, "noCat1", RoomStatus.OPEN);
        saveRoomNoCategory(owner, svc, "noCat2", RoomStatus.OPEN);

        List<DashboardLabelValueDto> dist = chartsService.categoryDistribution();
        long withCat = valueFor(dist, cat.getName());
        long withoutCat = valueFor(dist, "Без категории");
        // The category was just created in this test, so its name is unique —
        // no baseline needed there.
        assertEquals(1, withCat, "category-attached rooms are listed by category name");
        assertEquals(2, withoutCat - beforeWithoutCat,
                "two category-less rooms collapsed into the fallback label (delta)");
    }

    // ===================== room status distribution =====================

    @Test
    void roomStatusDistribution_countsByStatus() {
        // status-by-status counts span the whole `rooms` table, so once again
        // we compare a delta against a pre-insert baseline.
        List<DashboardLabelValueDto> before = chartsService.roomStatusDistribution();
        long beforeOpen = valueFor(before, RoomStatus.OPEN.name());
        long beforeActive = valueFor(before, RoomStatus.ACTIVE.name());

        User owner = saveUser("statOwner");
        Category cat = saveCategory("statCat");
        ServiceEntity svc = saveService(cat, "statSvc");
        saveRoom(owner, svc, cat, "o1", RoomStatus.OPEN);
        saveRoom(owner, svc, cat, "o2", RoomStatus.OPEN);
        saveRoom(owner, svc, cat, "a1", RoomStatus.ACTIVE);

        List<DashboardLabelValueDto> dist = chartsService.roomStatusDistribution();
        long open = valueFor(dist, RoomStatus.OPEN.name());
        long active = valueFor(dist, RoomStatus.ACTIVE.name());
        assertEquals(2, open - beforeOpen);
        assertEquals(1, active - beforeActive);
    }

    // ===================== fixtures =====================

    private User saveUser(String prefix) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("chart_" + prefix + "_" + n + "_" + System.nanoTime() + "@t.kz")
                .password("x")
                .displayName(prefix + " " + n)
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User saveUserWithPhone(String prefix, String phone) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("chart_p_" + prefix + "_" + n + "_" + System.nanoTime() + "@t.kz")
                .password("x")
                .displayName(prefix + " " + n)
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .phone(phone)
                .build());
    }

    private Category saveCategory(String prefix) {
        int n = SEQ.incrementAndGet();
        Category c = Category.builder()
                .name(truncate(prefix + " " + n, 100))
                .slug(truncate("c-" + n + "-" + System.nanoTime(), 120))
                .isActive(true)
                .build();
        return categoryRepository.save(c);
    }

    private ServiceEntity saveService(Category cat, String name) {
        int n = SEQ.incrementAndGet();
        ServiceEntity s = ServiceEntity.builder()
                .category(cat)
                .name(truncate(name + " " + n, 120))
                .slug(truncate("s-" + n + "-" + System.nanoTime(), 120))
                .providerType(ProviderType.DIGITAL)
                .isActive(true)
                .build();
        return serviceRepository.save(s);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Room saveRoom(User owner, ServiceEntity svc, Category cat, String title, RoomStatus status) {
        return saveRoomWithCurrency(owner, svc, cat, title, "KZT", status);
    }

    private Room saveRoomWithCurrency(User owner, ServiceEntity svc, Category cat,
                                       String title, String currency, RoomStatus status) {
        int n = SEQ.incrementAndGet();
        Room r = Room.builder()
                .owner(owner)
                .service(svc)
                .category(cat)
                .roomType(RoomType.DIGITAL)
                .verificationMode(VerificationMode.RISK_BASED)
                .status(status)
                .title(title + " " + n)
                .maxMembers(4)
                .priceTotal(new BigDecimal("4000"))
                .pricePerMember(new BigDecimal("1000"))
                .currency(currency)
                .fxRateToKzt(BigDecimal.ONE)
                .priceTotalKzt(new BigDecimal("4000"))
                .pricePerMemberKzt(new BigDecimal("1000"))
                .periodType(PeriodType.MONTHLY)
                .startDate(LocalDateTime.now().plusDays(1))
                .accessType(AccessType.SHARED_ACCOUNT)
                .operatorTermsConfirmed(false)
                .build();
        return roomRepository.save(r);
    }

    private Room saveRoomNoCategory(User owner, ServiceEntity svc, String title, RoomStatus status) {
        int n = SEQ.incrementAndGet();
        Room r = Room.builder()
                .owner(owner)
                .service(svc)
                .category(null)
                .roomType(RoomType.DIGITAL)
                .verificationMode(VerificationMode.RISK_BASED)
                .status(status)
                .title(title + " " + n)
                .maxMembers(3)
                .priceTotal(new BigDecimal("3000"))
                .pricePerMember(new BigDecimal("1000"))
                .currency("KZT")
                .fxRateToKzt(BigDecimal.ONE)
                .priceTotalKzt(new BigDecimal("3000"))
                .pricePerMemberKzt(new BigDecimal("1000"))
                .periodType(PeriodType.MONTHLY)
                .startDate(LocalDateTime.now().plusDays(1))
                .accessType(AccessType.SHARED_ACCOUNT)
                .operatorTermsConfirmed(false)
                .build();
        return roomRepository.save(r);
    }

    private RoomMember saveMember(Room room, User user, MemberStatus status) {
        RoomMember m = RoomMember.builder()
                .room(room)
                .user(user)
                .status(status)
                .requiresAdminReview(false)
                .build();
        return roomMemberRepository.save(m);
    }

    private long countFor(List<OperatorDistributionDto> dist, String code) {
        return dist.stream().filter(d -> code.equals(d.getCode())).mapToLong(OperatorDistributionDto::getCount).sum();
    }

    private long valueFor(List<DashboardLabelValueDto> dist, String label) {
        return dist.stream().filter(d -> label.equals(d.getLabel())).mapToLong(DashboardLabelValueDto::getValue).sum();
    }
}
