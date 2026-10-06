package kz.hrms.splitupauth.dto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import kz.hrms.splitupauth.config.JacksonAlmatyConfig;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the fix for the "Membership not found" class of bugs: CockroachDB unique_rowid() primary
 * keys exceed 2^53, so any id JavaScript parses as a Number loses precision and no longer matches
 * the stored row. Every id the frontend round-trips into a URL must therefore serialize as a JSON
 * string, while numeric counts/metrics must stay numbers.
 *
 * <p>Exercises the real production module ({@link JacksonAlmatyConfig#bigIntIdAsStringModule()}) on
 * the Jackson 3.x mapper that backs HTTP responses.
 */
class IdStringSerializationTest {

  private final JsonMapper mapper =
      JsonMapper.builder().addModule(JacksonAlmatyConfig.bigIntIdAsStringModule()).build();

  @Test
  void membershipIdSerializesAsString() {
    MyRoomMembershipDto dto =
        MyRoomMembershipDto.builder()
            .id(1190396672850886700L)
            .roomId(5L)
            .userId(7L)
            .status(MemberStatus.APPLIED)
            .build();

    String json = mapper.writeValueAsString(dto);

    assertTrue(
        json.contains("\"id\":\"1190396672850886700\""),
        "membership id must be a quoted string, got: " + json);
  }

  @Test
  void allIdFieldsInPaymentIntentAreStrings() {
    PaymentIntentResponse dto =
        PaymentIntentResponse.builder()
            .id(1190396672850886700L)
            .roomMemberId(1190396672850886701L)
            .amount(new BigDecimal("6531.67"))
            .status(PaymentIntentStatus.PENDING)
            .currency("KZT")
            .build();

    String json = mapper.writeValueAsString(dto);

    assertTrue(
        json.contains("\"id\":\"1190396672850886700\""),
        "intent id must be a quoted string, got: " + json);
    // roomMemberId ends in "Id" → also a round-tripped id → string.
    assertTrue(
        json.contains("\"roomMemberId\":\"1190396672850886701\""),
        "roomMemberId must be a quoted string, got: " + json);
    // The amount is a BigDecimal, never an id → stays a JSON number.
    assertTrue(json.contains("\"amount\":6531.67"), "amount must stay a JSON number, got: " + json);
  }

  @Test
  void roomMemberDtoIdSerializesAsString() {
    RoomMemberDto dto =
        RoomMemberDto.builder()
            .id(1190396672850886700L)
            .roomId(5L)
            .userId(7L)
            .status(MemberStatus.PENDING)
            .build();

    String json = mapper.writeValueAsString(dto);

    assertTrue(
        json.contains("\"id\":\"1190396672850886700\""),
        "room member id must be a quoted string (owner-access round-trip), got: " + json);
  }

  @Test
  void idFieldsAreStringsButCountFieldsStayNumbers() {
    ReputationDto dto =
        ReputationDto.builder()
            .userId(1190396672850886700L)
            .reviewsCount(12L)
            .completedRoomsCount(3L)
            .build();

    String json = mapper.writeValueAsString(dto);

    // userId ends in "Id" → string.
    assertTrue(
        json.contains("\"userId\":\"1190396672850886700\""),
        "userId must be a quoted string, got: " + json);
    // *Count fields are Long but not ids → they must stay JSON numbers so dashboards/math work.
    assertTrue(
        json.contains("\"reviewsCount\":12"), "reviewsCount must stay a JSON number, got: " + json);
    assertTrue(
        json.contains("\"completedRoomsCount\":3"),
        "completedRoomsCount must stay a JSON number, got: " + json);
  }
}
