package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.hrms.splitupauth.entity.Notification;
import kz.hrms.splitupauth.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * In-app notification as the bell UI consumes it. Doubles as the STOMP push
 * payload, so the live event and the persisted-list row have identical shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private Long id;
    private NotificationType type;
    private String title;
    private String body;
    private String link;
    // Plain map (not a raw JsonNode): a JsonNode read back from the jsonb column
    // serializes as its bean getters ({"array":false,...}) rather than its JSON
    // value, so we convert to a Map for a clean, stable wire shape.
    private Map<String, Object> metadata;
    private boolean read;
    private LocalDateTime createdAt;

    public static NotificationDto from(Notification n) {
        return NotificationDto.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .body(n.getBody())
                .link(n.getLink())
                .metadata(toMap(n.getMetadata()))
                .read(n.getReadAt() != null)
                .createdAt(n.getCreatedAt())
                .build();
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return MAPPER.convertValue(node, MAP_TYPE);
    }
}
