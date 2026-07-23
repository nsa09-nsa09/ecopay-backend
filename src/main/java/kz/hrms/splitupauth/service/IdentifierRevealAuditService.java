package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.repository.IdentifierRevealAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdentifierRevealAuditService {

  private final IdentifierRevealAuditRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      Room room,
      RoomMember roomMember,
      User actor,
      String actorRole,
      IdentifierRevealContextType contextType,
      Long contextId,
      IdentifierRevealReasonCode reasonCode,
      IdentifierRevealOutcome outcome,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    repository.save(
        IdentifierRevealAudit.builder()
            .eventId(UUID.randomUUID())
            .correlationId(correlationId)
            .actorUser(actor)
            .actorRole(actorRole)
            .room(room)
            .roomMember(roomMember)
            .contextType(contextType)
            .contextId(contextId)
            .reasonCode(reasonCode)
            .outcome(outcome)
            .requestId(correlationId)
            .clientIp(clientIp(request))
            .userAgent(userAgent(request))
            .createdAt(LocalDateTime.now())
            .build());
  }

  private UUID correlationId(HttpServletRequest request) {
    if (request == null) {
      return UUID.randomUUID();
    }
    String raw = request.getHeader("X-Request-ID");
    if (raw == null || raw.isBlank()) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException ex) {
      return UUID.randomUUID();
    }
  }

  private String clientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    String ip =
        forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",")[0].trim();
    return limit(ip, 64);
  }

  private String userAgent(HttpServletRequest request) {
    return request == null ? null : limit(request.getHeader("User-Agent"), 255);
  }

  private String limit(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
