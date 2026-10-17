package kz.hrms.splitupauth.service;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kz.hrms.splitupauth.dto.PublicHomeStatsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PublicHomeStatsService {

  private static final Duration CACHE_TTL = Duration.ofSeconds(60);

  private final EntityManager entityManager;
  private final Clock clock;

  private volatile PublicHomeStatsDto cached;
  private volatile Instant cachedAt = Instant.EPOCH;

  @Transactional(readOnly = true)
  public PublicHomeStatsDto getStats() {
    Instant now = Instant.now(clock);
    PublicHomeStatsDto snapshot = cached;
    if (snapshot != null && cachedAt.plus(CACHE_TTL).isAfter(now)) {
      return snapshot;
    }
    synchronized (this) {
      now = Instant.now(clock);
      snapshot = cached;
      if (snapshot != null && cachedAt.plus(CACHE_TTL).isAfter(now)) {
        return snapshot;
      }
      snapshot = loadStats();
      cached = snapshot;
      cachedAt = now;
      return snapshot;
    }
  }

  private PublicHomeStatsDto loadStats() {
    long totalUsers = singleLong("select count(u) from User u where u.deletedAt is null");
    long memberships =
        singleLong(
            """
            select count(m)
            from RoomMember m
            where m.deletedAt is null
              and m.status in (
                kz.hrms.splitupauth.entity.MemberStatus.PENDING,
                kz.hrms.splitupauth.entity.MemberStatus.ACTIVE
              )
            """);
    long activeRooms =
        singleLong(
            """
            select count(r)
            from Room r
            where r.deletedAt is null
              and r.status in (
                kz.hrms.splitupauth.entity.RoomStatus.OPEN,
                kz.hrms.splitupauth.entity.RoomStatus.ACTIVE
              )
            """);
    long verifiedReviews = verifiedReviewCount();
    BigDecimal averageRating =
        verifiedReviews == 0
            ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
            : verifiedAverageRating();

    return PublicHomeStatsDto.builder()
        .totalUsers(totalUsers)
        .completedOrActiveMemberships(memberships)
        .verifiedReviewCount(verifiedReviews)
        .averageVerifiedRating(averageRating)
        .activeRooms(activeRooms)
        .build();
  }

  private long verifiedReviewCount() {
    return singleLong(
        """
        select count(sr)
        from ServiceReview sr
        where exists (
          select 1
          from PaymentTransaction pt
          where pt.paymentIntent.user = sr.author
            and pt.type = kz.hrms.splitupauth.entity.PaymentTransactionType.CHARGE
            and pt.status = kz.hrms.splitupauth.entity.PaymentTransactionStatus.SUCCESS
        )
        or exists (
          select 1
          from RoomMember m
          where m.user = sr.author
            and m.deletedAt is null
            and m.status in (
              kz.hrms.splitupauth.entity.MemberStatus.PENDING,
              kz.hrms.splitupauth.entity.MemberStatus.ACTIVE
            )
        )
        """);
  }

  private BigDecimal verifiedAverageRating() {
    Double avg =
        entityManager
            .createQuery(
                """
                select avg(sr.rating)
                from ServiceReview sr
                where exists (
                  select 1
                  from PaymentTransaction pt
                  where pt.paymentIntent.user = sr.author
                    and pt.type = kz.hrms.splitupauth.entity.PaymentTransactionType.CHARGE
                    and pt.status = kz.hrms.splitupauth.entity.PaymentTransactionStatus.SUCCESS
                )
                or exists (
                  select 1
                  from RoomMember m
                  where m.user = sr.author
                    and m.deletedAt is null
                    and m.status in (
                      kz.hrms.splitupauth.entity.MemberStatus.PENDING,
                      kz.hrms.splitupauth.entity.MemberStatus.ACTIVE
                    )
                )
                """,
                Double.class)
            .getSingleResult();
    return BigDecimal.valueOf(avg == null ? 0.0 : avg).setScale(1, RoundingMode.HALF_UP);
  }

  private long singleLong(String jpql) {
    Long result = entityManager.createQuery(jpql, Long.class).getSingleResult();
    return result == null ? 0L : result;
  }
}
