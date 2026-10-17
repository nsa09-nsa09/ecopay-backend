package kz.hrms.splitupauth.service;

import java.util.Set;
import java.util.regex.Pattern;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns everything about the {@code User.slug} field: initial assignment from display name,
 * uniqueness enforcement (with numeric suffixes), and validated user-driven changes.
 *
 * <p>Reserved words guard routes that would otherwise collide with the app's URL structure — a
 * profile at {@code /u/admin} would shadow the admin panel entry point.
 */
@Service
@RequiredArgsConstructor
public class SlugService {

  static final int MAX_LENGTH = 30;
  private static final Pattern SLUG_PATTERN =
      Pattern.compile("^[a-z0-9]([a-z0-9-]{1,28}[a-z0-9])$");

  /**
   * Slugs that must not be handed out to end users because they overlap with existing top-level
   * routes on the web frontend.
   */
  static final Set<String> RESERVED =
      Set.of(
          "admin",
          "api",
          "u",
          "user",
          "users",
          "me",
          "profile",
          "settings",
          "login",
          "register",
          "catalog",
          "news",
          "support",
          "about",
          "room",
          "rooms",
          "static",
          "assets",
          "public",
          "help",
          "terms",
          "privacy");

  private final UserRepository userRepository;

  /**
   * Produce a unique slug based on the user's display name. If the base collides or is reserved, an
   * incrementing {@code -N} suffix is appended (trimming the base as needed to stay within {@link
   * #MAX_LENGTH}).
   */
  public String uniqueSlugFor(String displayName) {
    String base = SlugGenerator.normalize(displayName);
    return ensureUnique(base);
  }

  /** Assign the initial slug to a freshly created user. Persistence is the caller's job. */
  public void assignInitialSlug(User user) {
    user.setSlug(uniqueSlugFor(user.getDisplayName()));
  }

  /**
   * User-requested rename. Throws {@link InvalidRequestException} on malformed input and {@link
   * ResourceConflictException} when the target slug is reserved or taken.
   */
  public String changeSlug(User user, String requested) {
    String normalized = SlugGenerator.normalize(requested);
    boolean isFallbackForBlank =
        SlugGenerator.FALLBACK.equals(normalized)
            && (requested == null || requested.trim().isEmpty());
    if (isFallbackForBlank || !SLUG_PATTERN.matcher(normalized).matches()) {
      throw new InvalidRequestException("Invalid slug");
    }
    if (RESERVED.contains(normalized) || isTaken(normalized, user.getId())) {
      throw new ResourceConflictException("Slug is already taken");
    }
    user.setSlug(normalized);
    return normalized;
  }

  /**
   * Whether the given slug is claimed by any user other than the one identified by {@code
   * excludeUserId}.
   */
  public boolean isTaken(String slug, Long excludeUserId) {
    if (excludeUserId == null) {
      return userRepository.findBySlug(slug).isPresent();
    }
    return userRepository.existsBySlugAndIdNot(slug, excludeUserId);
  }

  private String ensureUnique(String base) {
    if (!RESERVED.contains(base) && !isTaken(base, null)) {
      return base;
    }
    int suffix = 2;
    while (true) {
      String suffixPart = "-" + suffix;
      int allowedBaseLen = MAX_LENGTH - suffixPart.length();
      String trimmedBase =
          base.length() > allowedBaseLen ? base.substring(0, allowedBaseLen) : base;
      // Truncation might leave a trailing dash — strip it so we don't produce "abc--2".
      while (trimmedBase.endsWith("-") && !trimmedBase.isEmpty()) {
        trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
      }
      if (trimmedBase.isEmpty()) {
        trimmedBase = SlugGenerator.FALLBACK;
      }
      String candidate = trimmedBase + suffixPart;
      if (!RESERVED.contains(candidate) && !isTaken(candidate, null)) {
        return candidate;
      }
      suffix++;
    }
  }
}
