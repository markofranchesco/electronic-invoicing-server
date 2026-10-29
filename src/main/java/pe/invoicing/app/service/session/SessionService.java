package pe.invoicing.app.service.session;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pe.invoicing.app.service.session.models.SessionDetails;

import java.util.UUID;

public interface SessionService {
  Page<SessionDetails> listSessions(Pageable pageable);

  void revokeSession(UUID sessionId);
}
