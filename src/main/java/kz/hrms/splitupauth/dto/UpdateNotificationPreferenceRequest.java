package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotNull;
import kz.hrms.splitupauth.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bulk preference update. The frontend sends the full set of rows it edited;
 * the service upserts each (user, type). Sending only changed rows is also
 * fine — untouched types keep their stored value / default.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateNotificationPreferenceRequest {

    @NotNull
    private List<Item> preferences;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @NotNull
        private NotificationType type;
        private boolean inApp;
        private boolean email;
    }
}
