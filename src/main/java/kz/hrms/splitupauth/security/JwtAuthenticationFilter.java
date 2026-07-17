package kz.hrms.splitupauth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String authHeader = request.getHeader("Authorization");

    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    try {
      String jwt = authHeader.substring(7);
      String subject = jwtUtil.extractUsername(jwt);

      if (subject != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        // New tokens carry the immutable publicId as subject; tokens minted
        // before the optional-email change carry the email. '@' cannot appear
        // in a publicId, so it cleanly discriminates the two.
        User user =
            (subject.contains("@")
                    ? userRepository.findByEmail(subject)
                    : userRepository.findByPublicId(subject))
                .orElse(null);

        if (user != null
            && user.getStatus() == UserStatus.ACTIVE
            && jwtUtil.validateToken(jwt, subject)) {

          UsernamePasswordAuthenticationToken authToken =
              new UsernamePasswordAuthenticationToken(
                  user, null, List.of(new SimpleGrantedAuthority(user.getRole().name())));

          authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authToken);
        }
      }
    } catch (Exception ignored) {
    }

    filterChain.doFilter(request, response);
  }
}
