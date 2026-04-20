package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomMemberIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoomMemberIdentifierRepository extends JpaRepository<RoomMemberIdentifier, Long> {
    Optional<RoomMemberIdentifier> findByRoomMember(RoomMember roomMember);
}