package pe.invoicing.app.service.session.port;

import pe.invoicing.app.service.session.models.AuthPrincipal;

public interface RequestIdentity {
  AuthPrincipal currentPrincipal();
}
