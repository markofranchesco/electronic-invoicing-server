package pe.invoicing.app.service.cancellation;

import pe.invoicing.app.service.cancellation.models.CancellationDetails;
import pe.invoicing.app.service.cancellation.models.CancellationState;

import java.util.UUID;

public interface CancellationService {
  CancellationState acceptCancellation(String documentNumber, UUID id, CancellationDetails body);
  CancellationState requestCancellation(String documentNumber, UUID id, CancellationDetails body);

  CancellationState getCancellation(String documentNumber, UUID id);

  CancellationState recoverCancellation(String documentNumber, UUID id, String ticket);
}
