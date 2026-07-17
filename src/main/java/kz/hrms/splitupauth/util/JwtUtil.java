package kz.hrms.splitupauth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  @Value("${jwt.secret}")
  private String secret;

  @Value("${jwt.access-expiration}")
  private Long accessExpiration;

  @Value("${jwt.refresh-expiration}")
  private Long refreshExpiration;

  private SecretKey getSigningKey() {
    byte[] keyBytes = Base64.getDecoder().decode(secret);
    return Keys.hmacShaKeyFor(keyBytes);
  }

  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  public Date extractExpiration(String token) {
    return extractClaim(token, Claims::getExpiration);
  }

  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = extractAllClaims(token);
    return claimsResolver.apply(claims);
  }

  private Claims extractAllClaims(String token) {
    return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
  }

  private Boolean isTokenExpired(String token) {
    return extractExpiration(token).before(new Date());
  }

  /**
   * Subject is the user's immutable publicId (tokens minted before the optional-email change carry
   * the email instead — JwtAuthenticationFilter resolves both).
   */
  public String generateAccessToken(String subject) {
    Map<String, Object> claims = new HashMap<>();
    return createToken(claims, subject, accessExpiration);
  }

  public String generateRefreshToken(String subject) {
    Map<String, Object> claims = new HashMap<>();
    return createToken(claims, subject, refreshExpiration);
  }

  private String createToken(Map<String, Object> claims, String subject, Long expiration) {
    return Jwts.builder()
        .claims(claims)
        .subject(subject)
        .issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + expiration))
        .signWith(getSigningKey())
        .compact();
  }

  public Boolean validateToken(String token, String email) {
    final String username = extractUsername(token);
    return (username.equals(email) && !isTokenExpired(token));
  }
}
