package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import kz.hrms.splitupauth.dto.MemberHoldDto;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberHoldService {

  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final PayoutRepository payoutRepository;
  private final Clock clock;

  @Transactional(readOnly = true)
  public MemberHoldDto getMyHold(Long roomId, User currentUser) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(existingRoom -> existingRoom.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    RoomMember roomMember =
        roomMemberRepository
            .findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

    List<Payout> held =
        payoutRepository.findHeldByRoomMember(
            roomMember,
            PayoutHoldPolicy.CURRENCY,
            PayoutHoldPolicy.HELD_STATUSES,
            LocalDateTime.now(clock));
    BigDecimal heldAmount =
        held.stream()
            .map(Payout::getPayableAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    LocalDateTime nextReleaseAt =
        held.stream()
            .map(Payout::getReleaseAt)
            .filter(Objects::nonNull)
            .min(LocalDateTime::compareTo)
            .orElse(null);
    User beneficiary = room.getOwner();

    return MemberHoldDto.builder()
        .heldAmount(heldAmount)
        .currency(PayoutHoldPolicy.CURRENCY)
        .heldPayoutCount(held.size())
        .nextReleaseAt(nextReleaseAt)
        .beneficiaryUserId(beneficiary.getId())
        .beneficiaryDisplayName(beneficiary.getDisplayName())
        .beneficiaryPublicId(beneficiary.getPublicId())
        .beneficiarySlug(beneficiary.getSlug())
        .build();
  }
}
