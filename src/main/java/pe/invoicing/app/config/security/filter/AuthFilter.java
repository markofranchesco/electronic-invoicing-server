package pe.invoicing.app.config.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import pe.invoicing.app.service.authentication.AutenticacionService;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class AuthFilter extends OncePerRequestFilter {
  private final AutenticacionService authentication;
  private final HandlerExceptionResolver errors;

  public AuthFilter(
          AutenticacionService authentication,
          @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
    this.authentication = authentication;
    this.errors = errors;
  }

  @Override
  protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    return List.of("/sign-in", "/sign-up", "/error").contains(request.getRequestURI())
            || "GET".equals(request.getMethod()) && request.getRequestURI().matches("/public/documents/[a-f0-9]{32}(?:/(?:XML|PDF|CDR|status))?")
            || "OPTIONS".equals(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
          @NonNull HttpServletRequest request,
          @NonNull HttpServletResponse response,
          @NonNull FilterChain chain)
          throws ServletException, IOException {
    try {
      SecurityContextHolder.getContext()
              .setAuthentication(
                      UsernamePasswordAuthenticationToken.authenticated(
                              authentication.authenticateBearerToken(
                                      Optional.ofNullable(request.getHeader("Authorization"))
                                              .filter(value -> value.startsWith("Bearer "))
                                              .map(value -> value.substring(7))
                                              .orElseThrow(
                                                      () ->
                                                              new ResponseStatusException(
                                                                      HttpStatus.UNAUTHORIZED, "Inicia sesión."))),
                              "",
                              List.of()));
    } catch (JwtException | IllegalArgumentException exception) {
      errors.resolveException(
              request,
              response,
              null,
              new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o vencido."));
      return;
    } catch (ResponseStatusException exception) {
      errors.resolveException(request, response, null, exception);
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
