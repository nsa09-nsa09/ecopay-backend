package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "Copy invite link" payload returned to the room owner.
 *
 * <p>{@code url} is built against {@code app.frontend-url} on the backend so
 * the link stays correct under whatever public domain the prod proxy fronts —
 * the frontend just renders it verbatim. {@code token} is exposed so the FE
 * can show / regenerate it later without re-parsing the URL.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomInviteLinkDto {
    private String url;
    private String token;
}
