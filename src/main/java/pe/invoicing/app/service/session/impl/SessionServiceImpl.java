package pe.invoicing.app.service.session.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.authentication.port.IdentityStore;
import pe.invoicing.app.service.session.SessionService;
import pe.invoicing.app.service.session.models.SessionDetails;
import pe.invoicing.app.service.session.models.TokenDetails;
import pe.invoicing.app.service.session.port.RequestIdentity;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {
  private final IdentityStore identityStore;
  private final RequestIdentity requestIdentity;

  @Override
  public Page<SessionDetails> listSessions(Pageable pageable) {
    return identityStore.listSessions(requestIdentity.currentPrincipal().accountId(), pageable);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void revokeSession(UUID sessionId) {
    TokenDetails session =
            identityStore
                    .findSessionById(sessionId, requestIdentity.currentPrincipal().accountId())
                    .orElseThrow(
                            () ->
                                    new ResponseStatusException(NOT_FOUND, "No encontramos la sesión solicitada."));
    identityStore.deleteSession(session.id());
  }
}
