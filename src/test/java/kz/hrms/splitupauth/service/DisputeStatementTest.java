package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.dto.CreateDisputeStatementRequest;
import kz.hrms.splitupauth.dto.DisputeStatementDto;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DisputeStatementTest {

  @Mock private DisputeRepository disputeRepository;
  @Mock private SupportTicketRepository supportTicketRepository;
  @Mock private AdminActionLogRepository adminActionLogRepository;
  @Mock private RoomEventLogRepository roomEventLogRepository;
  @Mock private RoomRepository roomRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private RefundTransactionRepository refundTransactionRepository;
  @Mock private UserRepository userRepository;
  @Mock private RefundService refundService;
  @Mock private ReputationService reputationService;
  @Mock private NotificationService notificationService;
  @Mock private PayoutBlockService payoutBlockService;
  @Mock private DisputeStatementRepository disputeStatementRepository;
  @Mock private SupportMessageRepository supportMessageRepository;

  private DisputeService service;

  @BeforeEach
  void setUp() {
    service =
        new DisputeService(
            disputeRepository,
            supportTicketRepository,
            adminActionLogRepository,
            roomEventLogRepository,
            roomRepository,
            roomMemberRepository,
            paymentTransactionRepository,
            refundTransactionRepository,
            userRepository,
            refundService,
            reputationService,
            notificationService,
            payoutBlockService,
            disputeStatementRepository,
            supportMessageRepository);
  }

  private static User user(Long id, Role role) {
    return User.builder()
        .id(id)
        .role(role)
        .status(UserStatus.ACTIVE)
        .displayName("User " + id)
        .build();
  }

  @Test
  void addStatement_memberCanWrite() {
    User owner = user(1L, Role.USER);
    User memberUser = user(2L, Role.USER);
    Room room = Room.builder().id(10L).owner(owner).build();
    RoomMember member = RoomMember.builder().id(20L).room(room).user(memberUser).build();
    Dispute dispute =
        Dispute.builder()
            .id(30L)
            .room(room)
            .roomMember(member)
            .openedByUser(memberUser)
            .status(DisputeStatus.OPEN)
            .build();

    when(disputeRepository.findById(30L)).thenReturn(Optional.of(dispute));
    when(disputeStatementRepository.findByDisputeAndSide(dispute, "MEMBER"))
        .thenReturn(Optional.empty());
    when(disputeStatementRepository.save(any(DisputeStatement.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CreateDisputeStatementRequest req = new CreateDisputeStatementRequest();
    req.setText("My side of the story with enough characters");

    DisputeStatementDto result = service.addStatement(30L, memberUser, req);

    assertEquals("MEMBER", result.getSide());
    assertNotNull(result.getText());
  }

  @Test
  void addStatement_ownerCanWrite() {
    User owner = user(1L, Role.USER);
    User memberUser = user(2L, Role.USER);
    Room room = Room.builder().id(10L).owner(owner).build();
    RoomMember member = RoomMember.builder().id(20L).room(room).user(memberUser).build();
    Dispute dispute =
        Dispute.builder()
            .id(30L)
            .room(room)
            .roomMember(member)
            .openedByUser(memberUser)
            .status(DisputeStatus.OPEN)
            .build();

    when(disputeRepository.findById(30L)).thenReturn(Optional.of(dispute));
    when(disputeStatementRepository.findByDisputeAndSide(dispute, "OWNER"))
        .thenReturn(Optional.empty());
    when(disputeStatementRepository.save(any(DisputeStatement.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CreateDisputeStatementRequest req = new CreateDisputeStatementRequest();
    req.setText("Owner's response to the complaint");

    DisputeStatementDto result = service.addStatement(30L, owner, req);

    assertEquals("OWNER", result.getSide());
  }

  @Test
  void addStatement_updatesExistingStatement() {
    User owner = user(1L, Role.USER);
    User memberUser = user(2L, Role.USER);
    Room room = Room.builder().id(10L).owner(owner).build();
    RoomMember member = RoomMember.builder().id(20L).room(room).user(memberUser).build();
    Dispute dispute =
        Dispute.builder()
            .id(30L)
            .room(room)
            .roomMember(member)
            .openedByUser(memberUser)
            .status(DisputeStatus.OPEN)
            .build();
    DisputeStatement existing =
        DisputeStatement.builder()
            .id(40L)
            .dispute(dispute)
            .author(memberUser)
            .side("MEMBER")
            .text("Old text old text old text")
            .build();

    when(disputeRepository.findById(30L)).thenReturn(Optional.of(dispute));
    when(disputeStatementRepository.findByDisputeAndSide(dispute, "MEMBER"))
        .thenReturn(Optional.of(existing));
    when(disputeStatementRepository.save(any(DisputeStatement.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CreateDisputeStatementRequest req = new CreateDisputeStatementRequest();
    req.setText("Updated statement text with enough chars");

    DisputeStatementDto result = service.addStatement(30L, memberUser, req);

    assertEquals(40L, result.getId());
    assertEquals("Updated statement text with enough chars", result.getText());
  }

  @Test
  void addStatement_rejectsNonParty() {
    User owner = user(1L, Role.USER);
    User memberUser = user(2L, Role.USER);
    User stranger = user(99L, Role.USER);
    Room room = Room.builder().id(10L).owner(owner).build();
    RoomMember member = RoomMember.builder().id(20L).room(room).user(memberUser).build();
    Dispute dispute =
        Dispute.builder()
            .id(30L)
            .room(room)
            .roomMember(member)
            .openedByUser(memberUser)
            .status(DisputeStatus.OPEN)
            .build();

    when(disputeRepository.findById(30L)).thenReturn(Optional.of(dispute));

    CreateDisputeStatementRequest req = new CreateDisputeStatementRequest();
    req.setText("I should not be allowed to write here");

    assertThrows(ForbiddenOperationException.class, () -> service.addStatement(30L, stranger, req));
  }

  @Test
  void addStatement_rejectsResolvedDispute() {
    User memberUser = user(2L, Role.USER);
    Room room = Room.builder().id(10L).owner(user(1L, Role.USER)).build();
    RoomMember member = RoomMember.builder().id(20L).room(room).user(memberUser).build();
    Dispute dispute =
        Dispute.builder()
            .id(30L)
            .room(room)
            .roomMember(member)
            .openedByUser(memberUser)
            .status(DisputeStatus.RESOLVED)
            .build();

    when(disputeRepository.findById(30L)).thenReturn(Optional.of(dispute));

    CreateDisputeStatementRequest req = new CreateDisputeStatementRequest();
    req.setText("Too late to add a statement now");

    assertThrows(InvalidRequestException.class, () -> service.addStatement(30L, memberUser, req));
  }

  @Test
  void getPartyDispute_returnsStatementsForParty() {
    User owner = user(1L, Role.USER);
    User memberUser = user(2L, Role.USER);
    Room room = Room.builder().id(10L).owner(owner).build();
    RoomMember member =
        RoomMember.builder()
            .id(20L)
            .room(room)
            .user(memberUser)
            .status(MemberStatus.ACTIVE)
            .build();
    Dispute dispute =
        Dispute.builder()
            .id(30L)
            .room(room)
            .roomMember(member)
            .openedByUser(memberUser)
            .status(DisputeStatus.OPEN)
            .build();

    when(disputeRepository.findById(30L)).thenReturn(Optional.of(dispute));
    when(disputeStatementRepository.findByDisputeOrderByCreatedAtAsc(dispute))
        .thenReturn(
            List.of(
                DisputeStatement.builder()
                    .id(40L)
                    .dispute(dispute)
                    .author(memberUser)
                    .side("MEMBER")
                    .text("Member statement")
                    .build()));

    var response = service.getPartyDispute(30L, memberUser);

    assertNotNull(response.getStatements());
    assertEquals(1, response.getStatements().size());
    assertEquals("MEMBER", response.getStatements().get(0).getSide());
  }
}
