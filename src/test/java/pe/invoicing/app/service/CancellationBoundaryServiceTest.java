package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CancellationBoundaryServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Motivo [{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {" ", "Motivo\ncon salto"})
  @DisplayName("Baja sin sustento válido")
  void whenReasonInvalidThenRejectBeforeReservingCommunication(String reason) {
    // El servicio también valida el sustento, incluso sin pasar por el controlador.
    var document = issue("invoices/cash/request.json", "invalid-void-reason");
    assertThatThrownBy(() -> cancellations.requestCancellation(COMPANY, document.getId(),
            new CancellationDetails(reason, "COMMUNICATION_BAJA", true)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(422));
    verify(store, never()).claimCancellation(any(), anyString(), any(), any());
    verify(gateway, never()).sendSummary(anyString(), anyString(), any());
  }
}
