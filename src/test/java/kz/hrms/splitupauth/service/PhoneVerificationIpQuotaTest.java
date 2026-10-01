package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.PhoneAlreadyExistsException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.repository.PhoneVerificationRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.sms.SmsProperties;
import kz.hrms.splitupauth.sms.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Per-IP caps on SMS code requests. The per-phone limits cannot see an attacker walking the number
 * space — every number it tries is that number's first request — and each walk step costs real
 * money at the provider, so this is the layer that has to hold.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhoneVerificationIpQuotaTest {

  private static final String IP = "203.0.113.7";

  @Mock private PhoneVerificationRepository verificationRepository;
  @Mock private UserRepository userRepository;
  @Mock private SmsService smsService;
  @Mock private PasswordEncoder passwordEncoder;

  private SmsProperties smsProperties;
  private PhoneVerificationService service;

  @BeforeEach
  void setUp() {
    smsProperties = new SmsProperties();
    smsProperties.setMaxPerIpPerHour(3);
    smsProperties.setMaxPerIpPerDay(5);

    service =
        new PhoneVerificationService(
            verificationRepository,
            userRepository,
            smsService,
            passwordEncoder,
            smsProperties,
            new InMemoryRateLimiter());

    lenient().when(userRepository.findByPhone(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(verificationRepository.countByPhoneAndCreatedAtAfter(anyString(), any()))
        .thenReturn(0L);
    lenient()
        .when(verificationRepository.findTopByPhoneOrderByCreatedAtDesc(anyString()))
        .thenReturn(Optional.empty());
    lenient().when(passwordEncoder.encode(anyString())).thenReturn("hash");
  }

  private static java.time.LocalDateTime any() {
    return org.mockito.ArgumentMatchers.any();
  }

  private MockHttpServletRequest request(String ip) {
    MockHttpServletRequest http = new MockHttpServletRequest();
    http.setRemoteAddr(ip);
    return http;
  }

  private User user(long id) {
    return User.builder().id(id).build();
  }

  @Test
  void oneIpWalkingDifferentNumbers_isStoppedByTheHourlyCap() {
    // Each number is "first request for that number", so the per-phone caps never
    // fire — only the IP cap stands between this loop and the SMS bill.
    for (int i = 0; i < 3; i++) {
      service.requestCode(user(1L), "+7700000000" + i, request(IP));
    }
    verify(smsService, times(3)).sendVerificationCode(anyString(), anyString());

    assertThrows(
        TooManyRequestsException.class,
        () -> service.requestCode(user(1L), "+77000000099", request(IP)));
    verify(smsService, times(3)).sendVerificationCode(anyString(), anyString());
  }

  @Test
  void aDifferentIpKeepsItsOwnAllowance() {
    for (int i = 0; i < 3; i++) {
      service.requestCode(user(1L), "+7700000000" + i, request(IP));
    }
    // Neighbour behind another address must not inherit the exhausted bucket.
    service.requestCode(user(2L), "+77011111111", request("198.51.100.4"));
    verify(smsService, times(4)).sendVerificationCode(anyString(), anyString());
  }

  @Test
  void theTakenNumberProbeAlsoCostsQuota() {
    // requestCode answers 409 for a number owned by someone else. That response is
    // an enumeration oracle, so it has to be charged like a send — otherwise
    // probing is free and unlimited.
    when(userRepository.findByPhone("+77012223344")).thenReturn(Optional.of(user(99L)));

    for (int i = 0; i < 3; i++) {
      assertThrows(
          PhoneAlreadyExistsException.class,
          () -> service.requestCode(user(1L), "+77012223344", request(IP)));
    }

    assertThrows(
        TooManyRequestsException.class,
        () -> service.requestCode(user(1L), "+77019998877", request(IP)));
    verify(smsService, never()).sendVerificationCode(anyString(), anyString());
  }

  @Test
  void theSilentResendPathIsChargedToo() {
    // Silent callers can enforce the same quota before deciding whether to send.
    for (int i = 0; i < 3; i++) {
      service.enforceIpQuota(request(IP));
    }
    assertThrows(TooManyRequestsException.class, () -> service.enforceIpQuota(request(IP)));
  }

  @Test
  void theProxiedClientAddressIsUsed_notTheProxysOwn() {
    MockHttpServletRequest http = request("10.0.0.1");
    http.addHeader("X-Forwarded-For", IP + ", 10.0.0.1");
    for (int i = 0; i < 3; i++) {
      service.enforceIpQuota(http);
    }

    // Same real client, fresh socket address → must still be the exhausted bucket.
    MockHttpServletRequest other = request("10.0.0.2");
    other.addHeader("X-Forwarded-For", IP + ", 10.0.0.2");
    assertThrows(TooManyRequestsException.class, () -> service.enforceIpQuota(other));
  }

  @Test
  void noRequestContext_skipsTheCheck() {
    // Internal callers (schedulers, tests) have no request; they must not blow up.
    for (int i = 0; i < 10; i++) {
      service.requestCode(user(1L), "+7700000000" + i, null);
    }
    verify(smsService, times(10)).sendVerificationCode(anyString(), anyString());
  }

  @Test
  void aZeroCapDisablesThatWindow() {
    smsProperties.setMaxPerIpPerHour(0);
    smsProperties.setMaxPerIpPerDay(0);
    for (int i = 0; i < 20; i++) {
      service.enforceIpQuota(request(IP));
    }
    assertEquals(0, smsProperties.getMaxPerIpPerHour());
  }
}
