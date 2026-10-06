package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import org.junit.jupiter.api.Test;

class RoomMemberMapperTest {

  private final RoomMemberMapper mapper = new RoomMemberMapper();

  @Test
  void toDtoIncludesPublicProfileIdentifiers() {
    User user =
        User.builder()
            .id(2L)
            .displayName("Member")
            .email("member@example.com")
            .publicId("public-123")
            .slug("member-slug")
            .reputation(80)
            .build();
    RoomMember member =
        RoomMember.builder()
            .id(3L)
            .room(Room.builder().id(1L).build())
            .user(user)
            .status(MemberStatus.ACTIVE)
            .build();

    RoomMemberDto dto = mapper.toDto(member);

    assertEquals("public-123", dto.getUserPublicId());
    assertEquals("member-slug", dto.getUserSlug());
  }
}
