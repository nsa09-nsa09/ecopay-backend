package kz.hrms.splitupauth.repository;

import java.util.Optional;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomMemberIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomMemberIdentifierRepository extends JpaRepository<RoomMemberIdentifier, Long> {
  Optional<RoomMemberIdentifier> findByRoomMember(RoomMember roomMember);
}
