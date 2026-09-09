package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.entity.VerificationMode;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.PayoutMethodRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

  @Mock private RoomRepository roomRepository;
  @Mock private CategoryRepository categoryRepository;
  @Mock private ServiceRepository serviceRepository;
  @Mock private TariffPlanRepository tariffPlanRepository;
  @Mock private RoomMapper roomMapper;
  @Mock private RoomEventLogger roomEventLogger;
  @Mock private ReviewRepository reviewRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private ReputationService reputationService;
  @Mock private PayoutMethodRepository payoutMethodRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private RefundService refundService;

  private RoomService roomService;

  @BeforeEach
  void setUp() {
    roomService =
        new RoomService(
            roomRepository,
            categoryRepository,
            serviceRepository,
            tariffPlanRepository,
            roomMapper,
            roomEventLogger,
            new ObjectMapper(),
            reviewRepository,
            roomMemberRepository,
            new ExchangeRateService(new ObjectMapper()),
            reputationService,
            payoutMethodRepository,
            paymentTransactionRepository,
            refundService);

    lenient()
        .when(roomRepository.saveAll(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void moveStartedOpenRoomsToVerification_doesNotMoveRoomsBeforeStartDate() {
    Room startedRoom = room(10L, LocalDateTime.now().minusMinutes(1));
    Room futureRoom = room(11L, LocalDateTime.now().plusMinutes(10));

    when(roomRepository.findByStatusAndDeletedAtIsNullAndStartDateLessThanEqual(
            eq(RoomStatus.OPEN), any(LocalDateTime.class)))
        .thenReturn(List.of(startedRoom, futureRoom));

    int movedRooms = roomService.moveStartedOpenRoomsToVerification();

    assertEquals(1, movedRooms);
    assertEquals(RoomStatus.IN_VERIFICATION, startedRoom.getStatus());
    assertNotNull(startedRoom.getReadyForVerificationAt());
    assertEquals(RoomStatus.OPEN, futureRoom.getStatus());
    assertNull(futureRoom.getReadyForVerificationAt());
    verify(roomRepository)
        .saveAll(
            argThat(
                rooms -> {
                  List<Room> savedRooms = new ArrayList<>();
                  rooms.forEach(savedRooms::add);
                  return savedRooms.size() == 1 && savedRooms.contains(startedRoom);
                }));
  }

  @Test
  void moveStartedOpenRoomsToVerification_movesRoomsAtStartDate() {
    Room room = room(12L, LocalDateTime.now());

    when(roomRepository.findByStatusAndDeletedAtIsNullAndStartDateLessThanEqual(
            eq(RoomStatus.OPEN), any(LocalDateTime.class)))
        .thenReturn(List.of(room));

    int movedRooms = roomService.moveStartedOpenRoomsToVerification();

    assertEquals(1, movedRooms);
    assertEquals(RoomStatus.IN_VERIFICATION, room.getStatus());
    assertNotNull(room.getReadyForVerificationAt());
    verify(roomRepository)
        .saveAll(
            argThat(
                rooms -> {
                  List<Room> savedRooms = new ArrayList<>();
                  rooms.forEach(savedRooms::add);
                  return savedRooms.size() == 1 && savedRooms.contains(room);
                }));
  }

  @Test
  void getRoomUsesPlainReadWithoutPessimisticLock() {
    Room room = room(21L, LocalDateTime.now().plusDays(1));
    RoomResponse response = RoomResponse.builder().maxMembers(3).build();

    when(roomRepository.findByIdAndDeletedAtIsNull(21L)).thenReturn(Optional.of(room));
    when(roomMapper.toResponse(room)).thenReturn(response);
    when(reviewRepository.aggregateRatingByRecipientIds(List.of(room.getOwner().getId())))
        .thenReturn(List.of());
    when(roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(eq(room), any()))
        .thenReturn(1L);

    RoomResponse result = roomService.getRoom(21L);

    assertEquals(2, result.getFilledSeats());
    assertEquals(1, result.getFreeSeats());
    verify(roomRepository).findByIdAndDeletedAtIsNull(21L);
    verify(roomRepository, never()).findByIdForUpdate(21L);
  }

  @Test
  void getRoomUsesExistingMembersCountInSeatMath() {
    Room room = room(31L, LocalDateTime.now().plusDays(1));
    room.setMaxMembers(6);
    room.setExistingMembersCount(3);
    RoomResponse response =
        RoomResponse.builder().maxMembers(6).existingMembersCount(3).marketplaceCapacity(3).build();

    when(roomRepository.findByIdAndDeletedAtIsNull(31L)).thenReturn(Optional.of(room));
    when(roomMapper.toResponse(room)).thenReturn(response);
    when(reviewRepository.aggregateRatingByRecipientIds(List.of(room.getOwner().getId())))
        .thenReturn(List.of());
    when(roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(eq(room), any()))
        .thenReturn(2L);

    RoomResponse result = roomService.getRoom(31L);

    assertEquals(3, result.getExistingMembersCount());
    assertEquals(3, result.getMarketplaceCapacity());
    assertEquals(5, result.getFilledSeats());
    assertEquals(1, result.getFreeSeats());
  }

  @Test
  void createRoomDefaultsMissingExistingMembersCountToOne() {
    User owner = user(7L);
    owner.setPhoneVerifiedAt(LocalDateTime.now());
    ServiceEntity service = service();
    TariffPlan tariff = tariff(service, 6);
    CreateRoomRequest request = createRoomRequest(service, tariff);
    AtomicReference<Room> saved = new AtomicReference<>();

    stubCreateRoomDependencies(owner, service, tariff);
    when(roomRepository.save(any(Room.class)))
        .thenAnswer(
            invocation -> {
              Room room = invocation.getArgument(0);
              saved.set(room);
              return room;
            });
    when(roomMapper.toResponse(any(Room.class))).thenReturn(RoomResponse.builder().build());

    roomService.createRoom(owner, request);

    assertEquals(1, saved.get().getExistingMembersCount());
    verify(roomMemberRepository, never()).save(any());
  }

  @Test
  void createRoomRejectsExistingMembersCountOutsideTariffCapacity() {
    User owner = user(8L);
    owner.setPhoneVerifiedAt(LocalDateTime.now());
    ServiceEntity service = service();
    TariffPlan tariff = tariff(service, 6);
    stubCreateRoomDependencies(owner, service, tariff);

    CreateRoomRequest zero = createRoomRequest(service, tariff);
    zero.setExistingMembersCount(0);
    assertThrows(kz.hrms.splitupauth.exception.InvalidRequestException.class, () -> roomService.createRoom(owner, zero));

    CreateRoomRequest full = createRoomRequest(service, tariff);
    full.setExistingMembersCount(6);
    assertThrows(kz.hrms.splitupauth.exception.InvalidRequestException.class, () -> roomService.createRoom(owner, full));
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
        .existingMembersCount(1)
        .priceTotal(BigDecimal.valueOf(3000))
        .currency("KZT")
        .periodType(PeriodType.MONTHLY)
        .startDate(startDate)
        .operatorTermsConfirmed(false)
        .build();
  }

  private void stubCreateRoomDependencies(User owner, ServiceEntity service, TariffPlan tariff) {
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(PayoutMethod.builder().id(1L).user(owner).status("ACTIVE").build()));
    lenient()
        .when(roomRepository.countByOwnerAndDeletedAtIsNullAndStatusIn(eq(owner), any()))
        .thenReturn(0L);
    when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
    when(tariffPlanRepository.findById(tariff.getId())).thenReturn(Optional.of(tariff));
  }

  private CreateRoomRequest createRoomRequest(ServiceEntity service, TariffPlan tariff) {
    CreateRoomRequest request = new CreateRoomRequest();
    request.setServiceId(service.getId());
    request.setTariffPlanId(tariff.getId());
    request.setTitle("Mixed room");
    return request;
  }

  private ServiceEntity service() {
    return ServiceEntity.builder()
        .id(100L)
        .name("Digital")
        .slug("digital")
        .providerType(ProviderType.DIGITAL)
        .isActive(true)
        .build();
  }

  private TariffPlan tariff(ServiceEntity service, int maxMembers) {
    return TariffPlan.builder()
        .id(200L)
        .service(service)
        .name("Family")
        .periodType(PeriodType.MONTHLY)
        .maxMembers(maxMembers)
        .basePriceTotal(new BigDecimal("6000.00"))
        .currency("KZT")
        .isActive(true)
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
