package kz.hrms.splitupauth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confirms the other half of the id-as-string contract: when the frontend sends an id back as a
 * JSON string (because that is what it received), the backend still binds it to the Long field.
 * Jackson coerces numeric strings into Long by default, so no @JsonDeserialize is needed.
 */
class IdStringDeserializationTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void longIdFieldsAcceptJsonStrings() {
    String body =
        "{\"categoryId\":\"1190396672850886700\",\"serviceId\":\"1190396672850886701\","
            + "\"tariffPlanId\":\"1190396672850886702\"}";

    CreateRoomRequest req = mapper.readValue(body, CreateRoomRequest.class);

    assertEquals(1190396672850886700L, req.getCategoryId());
    assertEquals(1190396672850886701L, req.getServiceId());
    assertEquals(1190396672850886702L, req.getTariffPlanId());
  }
}
