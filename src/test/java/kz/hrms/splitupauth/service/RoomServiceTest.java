package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.entity.VerificationMode;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private TariffPlanRepository tariffPlanRepository;
    @Mock
    private RoomMapper roomMapper;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(
                roomRepository,
                categoryRepository,
                serviceRepository,
                tariffPlanRepository,
                roomMapper
        );

        when(roomRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void moveStartedOpenRoomsToVerification_doesNotMoveRoomsBeforeStartDate() {
        Room startedRoom = room(10L, LocalDateTime.now().minusMinutes(1));
        Room futureRoom = room(11L, LocalDateTime.now().plusMinutes(10));

        when(roomRepository.findByStatusAndDeletedAtIsNullAndStartDateLessThanEqual(eq(RoomStatus.OPEN), any(LocalDateTime.class)))
                .thenReturn(List.of(startedRoom, futureRoom));

        int movedRooms = roomService.moveStartedOpenRoomsToVerification();

        assertEquals(1, movedRooms);
        assertEquals(RoomStatus.IN_VERIFICATION, startedRoom.getStatus());
        assertNotNull(startedRoom.getReadyForVerificationAt());
        assertEquals(RoomStatus.OPEN, futureRoom.getStatus());
        assertNull(futureRoom.getReadyForVerificationAt());
        verify(roomRepository).saveAll(argThat(rooms -> {
            List<Room> savedRooms = new ArrayList<>();
            rooms.forEach(savedRooms::add);
            return savedRooms.size() == 1 && savedRooms.contains(startedRoom);
        }));
    }

    @Test
    void moveStartedOpenRoomsToVerification_movesRoomsAtStartDate() {
        Room room = room(12L, LocalDateTime.now());

        when(roomRepository.findByStatusAndDeletedAtIsNullAndStartDateLessThanEqual(eq(RoomStatus.OPEN), any(LocalDateTime.class)))
                .thenReturn(List.of(room));

        int movedRooms = roomService.moveStartedOpenRoomsToVerification();

        assertEquals(1, movedRooms);
        assertEquals(RoomStatus.IN_VERIFICATION, room.getStatus());
        assertNotNull(room.getReadyForVerificationAt());
        verify(roomRepository).saveAll(argThat(rooms -> {
            List<Room> savedRooms = new ArrayList<>();
            rooms.forEach(savedRooms::add);
            return savedRooms.size() == 1 && savedRooms.contains(room);
        }));
    }

    private Room room(Long roomId, LocalDateTime startDate) {
        return Room.builder()
                .id(roomId)
                .owner(user(1L))
                .roomType(RoomType.DIGITAL)
                .verificationMode(VerificationMode.RISK_BASED)
                .status(RoomStatus.OPEN)
                .title("Test room")
                .description("Test description")
                .maxMembers(3)
                .priceTotal(BigDecimal.valueOf(3000))
                .currency("KZT")
                .periodType(PeriodType.MONTHLY)
                .startDate(startDate)
                .operatorTermsConfirmed(false)
                .build();
    }

    private User user(Long userId) {
        return User.builder()
                .id(userId)
                .email("user" + userId + "@example.com")
                .password("secret")
                .role(Role.USER)
                .displayName("User " + userId)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
