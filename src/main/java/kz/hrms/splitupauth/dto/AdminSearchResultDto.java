package kz.hrms.splitupauth.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response envelope for {@code GET /api/v1/admin/search}. Compact entries carry just enough to
 * render a hit in the global suggest dropdown and link out to the relevant admin detail page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSearchResultDto {

  /** Matching rooms. {@code id} is the room id; {@code label} is the room title. */
  @Builder.Default private List<Item> rooms = List.of();

  /**
   * Matching users. {@code id} is the user id; {@code label} is {@code "displayName <email>"};
   * {@code sublabel} carries phone when available so the operator can confirm before clicking
   * through.
   */
  @Builder.Default private List<Item> users = List.of();

  /**
   * Matching feedback rows. {@code id} is the feedback id; {@code label} is the subject (or the
   * first line of the message when subject is blank); {@code sublabel} carries the snippet that
   * matched.
   */
  @Builder.Default private List<Item> feedback = List.of();

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Item {
    /** Stable primary-key id of the row. */
    private Long id;

    /** Group ("room" | "user" | "feedback") — let the FE build the URL. */
    private String type;

    /** Primary label shown in the suggest row. */
    private String label;

    /** Optional secondary line (email / phone / status / snippet). */
    private String sublabel;
  }
}
