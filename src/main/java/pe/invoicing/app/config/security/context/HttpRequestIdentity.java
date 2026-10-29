package pe.invoicing.app.config.security.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.session.models.AuthPrincipal;
import pe.invoicing.app.service.session.port.RequestIdentity;

import java.util.Optional;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class HttpRequestIdentity implements RequestIdentity {
  @Override
  public AuthPrincipal currentPrincipal() {
    return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .map(Authentication::getPrincipal)
            .filter(AuthPrincipal.class::isInstance)
            .map(AuthPrincipal.class::cast)
            .orElseThrow(
                    () -> new ResponseStatusException(UNAUTHORIZED, "Inicia sesión para continuar."));
  }
}
