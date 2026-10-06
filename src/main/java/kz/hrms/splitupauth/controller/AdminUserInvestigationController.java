package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.AdminUserDto;
import kz.hrms.splitupauth.dto.UserInvestigationDto;
import kz.hrms.splitupauth.dto.UserReportDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserInvestigationController {
  private final UserRepository userRepository;
  private final RoomRepository roomRepository;
  private final RoomMemberRepository memberRepository;
  private final UserReportRepository reportRepository;
  private final SupportTicketRepository ticketRepository;
  private final DisputeRepository disputeRepository;
  private final RoomEventLogRepository eventRepository;
  private final AdminActionLogRepository actionRepository;

  @GetMapping("/{userId}/investigation")
  @Transactional(readOnly = true)
  public UserInvestigationDto get(@PathVariable Long userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    long tickets = ticketRepository.countByUser(user);
    long disputes = disputeRepository.countByOpenedByUser(user);
    var rooms = roomRepository.findByOwnerOrderByCreatedAtDesc(user, PageRequest.of(0, 50));
    var memberships = memberRepository.findByUserOrderByCreatedAtDesc(user, PageRequest.of(0, 50));
    var against =
        reportRepository.findByTargetUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20));
    var by = reportRepository.findByReporter_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20));
    var events = eventRepository.findByActorUserOrderByCreatedAtDesc(user, PageRequest.of(0, 20));
    var actions =
        actionRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            "USER", userId, PageRequest.of(0, 20));
    AdminUserDto summary =
        AdminUserDto.fromWithCounters(
            user,
            roomRepository.countByOwnerAndDeletedAtIsNull(user),
            memberRepository.countByUserAndDeletedAtIsNull(user),
            tickets,
            disputes);
    return new UserInvestigationDto(
        summary,
        rooms.stream()
            .map(
                r ->
                    new UserInvestigationDto.OwnedRoom(
                        r.getId(),
                        r.getTitle(),
                        r.getStatus().name(),
                        r.getCreatedAt(),
                        r.getDeletedAt()))
            .toList(),
        memberships.stream()
            .map(
                m ->
                    new UserInvestigationDto.Membership(
                        m.getRoom().getId(),
                        m.getRoom().getTitle(),
                        m.getStatus().name(),
                        m.getCreatedAt(),
                        m.getEndedAt()))
            .toList(),
        against.stream().map(r -> UserReportDto.from(r, false)).toList(),
        by.stream().map(r -> UserReportDto.from(r, false)).toList(),
        tickets,
        disputes,
        events.stream()
            .map(
                e ->
                    new UserInvestigationDto.RoomEvent(
                        e.getId(), e.getRoom().getId(), e.getEventType(), e.getCreatedAt()))
            .toList(),
        actions.stream()
            .map(
                a ->
                    new UserInvestigationDto.AdminAction(
                        a.getId(), a.getActionType().name(), a.getCreatedAt()))
            .toList());
  }
}
