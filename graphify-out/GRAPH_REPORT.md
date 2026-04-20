# Graph Report - src  (2026-04-18)

## Corpus Check
- Corpus is ~18,062 words - fits in a single context window. You may not need a graph.

## Summary
- 618 nodes · 944 edges · 48 communities detected
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## God Nodes (most connected - your core abstractions)
1. `RoomMemberService` - 26 edges
2. `RoomMemberServiceTest` - 21 edges
3. `SupportTicketService` - 18 edges
4. `RoomService` - 15 edges
5. `RoomRepository` - 14 edges
6. `ModerationService` - 14 edges
7. `DisputeService` - 13 edges
8. `GlobalExceptionHandler` - 11 edges
9. `JwtUtil` - 11 edges
10. `AdminModerationController` - 8 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Communities

### Community 0 - "Admin Logs and Catalog"
Cohesion: 0.03
Nodes (28): AdminActionLogDto, AdminActionLogFilterRequest, CatalogMapper, Category, CategoryDto, CategoryRepository, CreateRoomRequest, FieldEncryptionService (+20 more)

### Community 1 - "Auth and Admin Controllers"
Cohesion: 0.05
Nodes (11): AdminLogController, AuthController, JwtAuthenticationFilter, RoomMemberController, UpdateProfileRequest, User, UserController, UserDto (+3 more)

### Community 2 - "Support Ticket System"
Cohesion: 0.05
Nodes (11): CreateSupportMessageRequest, CreateSupportTicketRequest, StaffSupportTicketController, SupportMessage, SupportMessageDto, SupportMessageRepository, SupportTicket, SupportTicketController (+3 more)

### Community 3 - "Admin Audit and Refunds"
Cohesion: 0.06
Nodes (10): AdminActionLog, AdminActionLogRepository, AdminRefundController, CreateRefundRequest, Dispute, DisputeRepository, RefundTransaction, RefundTransactionRepository (+2 more)

### Community 4 - "Payment Processing"
Cohesion: 0.07
Nodes (9): ConfirmPaymentRequest, CreatePaymentIntentRequest, PaymentController, PaymentIntent, PaymentIntentRepository, PaymentIntentResponse, PaymentService, PaymentTransaction (+1 more)

### Community 5 - "Room Membership and Scheduling"
Cohesion: 0.07
Nodes (9): JoinRoomRequest, MyRoomMembershipDto, PendingMembershipEscalationScheduler, RoomMember, RoomMemberDto, RoomMemberIdentifier, RoomMemberIdentifierRepository, RoomMemberMapper (+1 more)

### Community 6 - "Room Member Access Control"
Cohesion: 0.15
Nodes (1): RoomMemberService

### Community 7 - "Admin Dispute Management"
Cohesion: 0.11
Nodes (7): AdminDisputeController, ApplyDisputeSanctionsRequest, DisputeController, DisputeDecisionRequest, DisputeResponse, PageResponse, StaffDisputeController

### Community 8 - "Room Member Service Tests"
Cohesion: 0.36
Nodes (1): RoomMemberServiceTest

### Community 9 - "Auth Cleanup and Login Tracking"
Cohesion: 0.11
Nodes (5): CleanupScheduler, LoginAttempt, LoginAttemptRepository, RateLimitService, TooManyLoginAttemptsException

### Community 10 - "Support Ticket Service"
Cohesion: 0.24
Nodes (1): SupportTicketService

### Community 11 - "Admin Moderation Controls"
Cohesion: 0.15
Nodes (4): AdminDecisionRequest, AdminModerationController, BatchConfirmRequest, ModerationQueueItemDto

### Community 12 - "Room Lifecycle Management"
Cohesion: 0.24
Nodes (1): RoomService

### Community 13 - "Room Data Repository"
Cohesion: 0.14
Nodes (1): RoomRepository

### Community 14 - "Moderation Service"
Cohesion: 0.32
Nodes (1): ModerationService

### Community 15 - "Dispute Resolution Service"
Cohesion: 0.36
Nodes (1): DisputeService

### Community 16 - "Error Handling"
Cohesion: 0.17
Nodes (1): GlobalExceptionHandler

### Community 17 - "Moderation Queue"
Cohesion: 0.18
Nodes (2): ModerationQueue, ModerationQueueRepository

### Community 18 - "JWT Token Utilities"
Cohesion: 0.36
Nodes (1): JwtUtil

### Community 19 - "Room API Controller"
Cohesion: 0.25
Nodes (1): RoomController

### Community 20 - "Refund Service"
Cohesion: 0.67
Nodes (1): RefundService

### Community 21 - "Staff Identity Reveal"
Cohesion: 0.29
Nodes (3): RevealedIdentifierDto, RevealIdentifierRequest, StaffRoomMemberController

### Community 22 - "Admin Log Service"
Cohesion: 0.52
Nodes (1): AdminLogService

### Community 23 - "Authentication Service"
Cohesion: 0.38
Nodes (1): AuthService

### Community 24 - "Room Service Tests"
Cohesion: 0.53
Nodes (1): RoomServiceTest

### Community 25 - "Catalog API"
Cohesion: 0.4
Nodes (1): CatalogController

### Community 26 - "Field Encryption"
Cohesion: 0.6
Nodes (1): AesFieldEncryptionService

### Community 27 - "Catalog Service"
Cohesion: 0.4
Nodes (1): CatalogService

### Community 28 - "Security Configuration"
Cohesion: 0.5
Nodes (1): SecurityConfig

### Community 29 - "Email Service"
Cohesion: 0.67
Nodes (1): EmailService

### Community 30 - "Application Entry Point"
Cohesion: 0.67
Nodes (1): SplitUpAuthApplication

### Community 31 - "OpenAPI Configuration"
Cohesion: 0.67
Nodes (1): OpenApiConfig

### Community 32 - "Error Response Model"
Cohesion: 0.67
Nodes (1): ErrorResponse

### Community 33 - "User Already Exists Error"
Cohesion: 0.67
Nodes (1): UserAlreadyExistsException

### Community 34 - "User Banned Exception"
Cohesion: 0.67
Nodes (1): UserBannedException

### Community 35 - "Application Context Test"
Cohesion: 0.67
Nodes (1): SplitUpAuthApplicationTests

### Community 36 - "Auth Response DTO"
Cohesion: 1.0
Nodes (1): AuthResponse

### Community 37 - "Block Room Request DTO"
Cohesion: 1.0
Nodes (1): BlockRoomRequest

### Community 38 - "Cancel Room Request DTO"
Cohesion: 1.0
Nodes (1): CancelRoomRequest

### Community 39 - "Owner Access Request DTO"
Cohesion: 1.0
Nodes (1): ConfirmOwnerAccessRequest

### Community 40 - "Login Request DTO"
Cohesion: 1.0
Nodes (1): LoginRequest

### Community 41 - "Register Request DTO"
Cohesion: 1.0
Nodes (1): RegisterRequest

### Community 42 - "Admin Action Types Enum"
Cohesion: 1.0
Nodes (0): 

### Community 43 - "Identifier Reveal Context"
Cohesion: 1.0
Nodes (0): 

### Community 44 - "Payment Transaction Types"
Cohesion: 1.0
Nodes (0): 

### Community 45 - "Refund Status Enum"
Cohesion: 1.0
Nodes (0): 

### Community 46 - "Support Sender Roles"
Cohesion: 1.0
Nodes (0): 

### Community 47 - "Support Ticket Priority"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **42 isolated node(s):** `AdminActionLogDto`, `AdminActionLogFilterRequest`, `AdminDecisionRequest`, `ApplyDisputeSanctionsRequest`, `AuthResponse` (+37 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Auth Response DTO`** (2 nodes): `AuthResponse.java`, `AuthResponse`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Block Room Request DTO`** (2 nodes): `BlockRoomRequest.java`, `BlockRoomRequest`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Cancel Room Request DTO`** (2 nodes): `CancelRoomRequest.java`, `CancelRoomRequest`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Owner Access Request DTO`** (2 nodes): `ConfirmOwnerAccessRequest.java`, `ConfirmOwnerAccessRequest`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Login Request DTO`** (2 nodes): `LoginRequest.java`, `LoginRequest`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Register Request DTO`** (2 nodes): `RegisterRequest.java`, `RegisterRequest`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Admin Action Types Enum`** (1 nodes): `AdminActionType.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Identifier Reveal Context`** (1 nodes): `IdentifierRevealContextType.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Payment Transaction Types`** (1 nodes): `PaymentTransactionType.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Refund Status Enum`** (1 nodes): `RefundStatus.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Support Sender Roles`** (1 nodes): `SupportSenderRole.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Support Ticket Priority`** (1 nodes): `SupportTicketPriority.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `RoomMemberService` connect `Room Member Access Control` to `Admin Logs and Catalog`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **Why does `RoomMemberServiceTest` connect `Room Member Service Tests` to `Admin Logs and Catalog`?**
  _High betweenness centrality (0.055) - this node is a cross-community bridge._
- **Why does `SupportTicketService` connect `Support Ticket Service` to `Support Ticket System`?**
  _High betweenness centrality (0.047) - this node is a cross-community bridge._
- **What connects `AdminActionLogDto`, `AdminActionLogFilterRequest`, `AdminDecisionRequest` to the rest of the system?**
  _42 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Admin Logs and Catalog` be split into smaller, more focused modules?**
  _Cohesion score 0.03 - nodes in this community are weakly interconnected._
- **Should `Auth and Admin Controllers` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `Support Ticket System` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._