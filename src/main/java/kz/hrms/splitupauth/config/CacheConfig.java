package kz.hrms.splitupauth.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory Caffeine cache for the public catalog reads. The catalog changes rarely and only from
 * the admin panel, so a per-instance cache is enough — no Redis needed. Every admin write path
 * calls {@code @CacheEvict(allEntries=true)} on the four caches below, so users see the change
 * immediately; the {@code expireAfterWrite} window is only a safety net for edge cases (bypassed
 * writes, restarts, clock skew) so entries can't go stale for longer than 10 minutes.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  static final List<String> CATALOG_CACHES =
      List.of("catalogCategories", "catalogServices", "catalogService", "catalogTariffs");

  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setCaffeine(
        Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(1000));
    manager.setCacheNames(CATALOG_CACHES);
    return manager;
  }
}
