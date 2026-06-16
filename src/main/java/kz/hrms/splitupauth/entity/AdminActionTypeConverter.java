package kz.hrms.splitupauth.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-tolerant converter for {@code admin_action_log.action_type}.
 *
 * <p>Writes are strict — the original enum name is sent to the DB unchanged,
 * so the append-only CHECK constraint still rejects anything the application
 * didn't intend. Reads, on the other hand, are guarded: an action_type that
 * doesn't match any current enum constant maps to
 * {@link AdminActionType#UNKNOWN} instead of throwing, which keeps the admin
 * /logs page from 500-ing if an old deployment left a since-renamed value
 * behind or a newer deployment writes a value this build doesn't yet ship
 * (the historical FEEDBACK_STATUS_CHANGED incident).
 *
 * <p>{@code autoApply = false} so this only applies to fields that opt in via
 * {@code @Convert}, not every {@link AdminActionType} field in the codebase.
 */
@Slf4j
@Converter(autoApply = false)
public class AdminActionTypeConverter implements AttributeConverter<AdminActionType, String> {

    @Override
    public String convertToDatabaseColumn(AdminActionType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public AdminActionType convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return AdminActionType.valueOf(dbData);
        } catch (IllegalArgumentException ex) {
            log.warn("unknown admin_action_log.action_type '{}' — mapped to UNKNOWN sentinel", dbData);
            return AdminActionType.UNKNOWN;
        }
    }
}
