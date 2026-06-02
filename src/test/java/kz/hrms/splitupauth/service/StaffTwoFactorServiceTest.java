package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallenge;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallengeStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.TwoFactorChallengeException;
import kz.hrms.splitupauth.repository.StaffTwoFactorChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffTwoFactorServiceTest {

    @Mock
    private StaffTwoFactorChallengeRepository challengeRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    private StaffTwoFactorService service;

    @BeforeEach
    void setUp() {
        service = new StaffTwoFactorService(challengeRepository, passwordEncoder, emailService);
        lenient().when(challengeRepository.save(any(StaffTwoFactorChallenge.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void requiresTwoFactor_isTrue_for_admin_and_support_only() {
        assertTrue(service.requiresTwoFactor(staffUser(Role.ADMIN)));
        assertTrue(service.requiresTwoFactor(staffUser(Role.SUPPORT)));
        User regular = staffUser(Role.USER);
        assertEquals(false, service.requiresTwoFactor(regular));
    }

    @Test
    void createChallenge_storesHashedCode_andSendsEmail() {
        User user = staffUser(Role.ADMIN);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        StaffTwoFactorChallenge challenge = service.createChallenge(user);

        ArgumentCaptor<StaffTwoFactorChallenge> savedCaptor =
                ArgumentCaptor.forClass(StaffTwoFactorChallenge.class);
        verify(challengeRepository).save(savedCaptor.capture());
        StaffTwoFactorChallenge saved = savedCaptor.getValue();

        assertNotNull(saved.getId());
        assertEquals("HASH", saved.getCodeHash());
        assertEquals(StaffTwoFactorChallengeStatus.PENDING, saved.getStatus());
        assertEquals(0, saved.getAttempts());
        assertNull(saved.getUsedAt());
        assertNotNull(saved.getExpiresAt());

        // Emailed plaintext code must be a 6-digit string and the same instance is returned.
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendStaffTwoFactorCode(eq(user.getEmail()), codeCaptor.capture());
        String sentCode = codeCaptor.getValue();
        assertTrue(sentCode.matches("\\d{6}"));
        assertSame(saved, challenge);
    }

    @Test
    void createChallenge_throws_forUserRole() {
        assertThrows(TwoFactorChallengeException.class,
                () -> service.createChallenge(staffUser(Role.USER)));
    }

    @Test
    void verifyChallenge_returnsUser_onCorrectCode_andMarksUsed() {
        User user = staffUser(Role.ADMIN);
        StaffTwoFactorChallenge challenge = pendingChallenge(user);
        when(challengeRepository.findById("c-1")).thenReturn(Optional.of(challenge));
        when(passwordEncoder.matches("123456", "HASH")).thenReturn(true);

        User verified = service.verifyChallenge("c-1", "123456");

        assertSame(user, verified);
        assertEquals(StaffTwoFactorChallengeStatus.VERIFIED, challenge.getStatus());
        assertNotNull(challenge.getUsedAt());
    }

    @Test
    void verifyChallenge_incrementsAttempts_onWrongCode() {
        User user = staffUser(Role.ADMIN);
        StaffTwoFactorChallenge challenge = pendingChallenge(user);
        when(challengeRepository.findById("c-1")).thenReturn(Optional.of(challenge));
        when(passwordEncoder.matches("000000", "HASH")).thenReturn(false);

        assertThrows(TwoFactorChallengeException.class,
                () -> service.verifyChallenge("c-1", "000000"));
        assertEquals(1, challenge.getAttempts());
        assertEquals(StaffTwoFactorChallengeStatus.PENDING, challenge.getStatus());
    }

    @Test
    void verifyChallenge_marksFailed_afterMaxAttempts() {
        User user = staffUser(Role.ADMIN);
        StaffTwoFactorChallenge challenge = pendingChallenge(user);
        challenge.setAttempts(StaffTwoFactorService.MAX_ATTEMPTS - 1);
        when(challengeRepository.findById("c-1")).thenReturn(Optional.of(challenge));
        when(passwordEncoder.matches(anyString(), eq("HASH"))).thenReturn(false);

        assertThrows(TwoFactorChallengeException.class,
                () -> service.verifyChallenge("c-1", "111111"));
        assertEquals(StaffTwoFactorService.MAX_ATTEMPTS, challenge.getAttempts());
        assertEquals(StaffTwoFactorChallengeStatus.FAILED, challenge.getStatus());
    }

    @Test
    void verifyChallenge_rejects_whenChallengeIsExpired() {
        User user = staffUser(Role.ADMIN);
        StaffTwoFactorChallenge challenge = pendingChallenge(user);
        challenge.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(challengeRepository.findById("c-1")).thenReturn(Optional.of(challenge));

        assertThrows(TwoFactorChallengeException.class,
                () -> service.verifyChallenge("c-1", "123456"));
        assertEquals(StaffTwoFactorChallengeStatus.EXPIRED, challenge.getStatus());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void verifyChallenge_rejects_whenAlreadyUsed() {
        User user = staffUser(Role.ADMIN);
        StaffTwoFactorChallenge challenge = pendingChallenge(user);
        challenge.setStatus(StaffTwoFactorChallengeStatus.VERIFIED);
        challenge.setUsedAt(LocalDateTime.now().minusMinutes(1));
        when(challengeRepository.findById("c-1")).thenReturn(Optional.of(challenge));

        assertThrows(TwoFactorChallengeException.class,
                () -> service.verifyChallenge("c-1", "123456"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void verifyChallenge_rejects_unknownId() {
        when(challengeRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(TwoFactorChallengeException.class,
                () -> service.verifyChallenge("missing", "123456"));
    }

    @Test
    void resendChallenge_rejects_whenInsideCooldown() {
        User user = staffUser(Role.ADMIN);
        StaffTwoFactorChallenge challenge = pendingChallenge(user);
        challenge.setLastSentAt(LocalDateTime.now().minusSeconds(1));
        when(challengeRepository.findById("c-1")).thenReturn(Optional.of(challenge));

        assertThrows(TwoFactorChallengeException.class,
                () -> service.resendChallenge("c-1"));
        verify(emailService, never()).sendStaffTwoFactorCode(anyString(), anyString());
    }

    @Test
    void resendChallenge_rotatesCode_andSendsEmail_whenCooldownPassed() {
        User user = staffUser(Role.ADMIN);
        StaffTwoFactorChallenge challenge = pendingChallenge(user);
        challenge.setLastSentAt(LocalDateTime.now()
                .minusSeconds(StaffTwoFactorService.RESEND_COOLDOWN_SECONDS + 5));
        when(challengeRepository.findById("c-1")).thenReturn(Optional.of(challenge));
        when(passwordEncoder.encode(anyString())).thenReturn("HASH2");

        service.resendChallenge("c-1");

        assertEquals("HASH2", challenge.getCodeHash());
        verify(emailService, times(1)).sendStaffTwoFactorCode(eq(user.getEmail()), anyString());
    }

    @Test
    void maskEmail_keepsFirstLetterAndDomain() {
        assertEquals("a***@example.com", StaffTwoFactorService.maskEmail("alex@example.com"));
        assertEquals("a*@x.io", StaffTwoFactorService.maskEmail("ab@x.io"));
    }

    private static User staffUser(Role role) {
        return User.builder()
                .id(7L)
                .email("alex@example.com")
                .password("PWD")
                .displayName("Alex")
                .role(role)
                .status(UserStatus.ACTIVE)
                .reputation(0)
                .emailVerified(true)
                .build();
    }

    private static StaffTwoFactorChallenge pendingChallenge(User user) {
        return StaffTwoFactorChallenge.builder()
                .id("c-1")
                .user(user)
                .codeHash("HASH")
                .status(StaffTwoFactorChallengeStatus.PENDING)
                .attempts(0)
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .lastSentAt(LocalDateTime.now().minusMinutes(1))
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }
}
