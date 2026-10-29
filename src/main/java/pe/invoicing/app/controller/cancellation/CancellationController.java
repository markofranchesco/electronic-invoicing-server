package pe.invoicing.app.controller.cancellation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.cancellation.dtos.CancellationApiResponse;
import pe.invoicing.app.controller.cancellation.dtos.CancellationRequest;
import pe.invoicing.app.controller.cancellation.mappers.CancellationMapper;
import pe.invoicing.app.controller.document.dtos.requests.RecoveryRequest;
import pe.invoicing.app.controller.shared.dtos.responses.AsyncOperationResponse;
import pe.invoicing.app.service.cancellation.CancellationService;

import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/companies/{documentNumber}/documents")
public class CancellationController {
  private final CancellationService cancellations;
  private final CancellationMapper mapper;

  @PostMapping("/{documentId}/cancel")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public AsyncOperationResponse cancel(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                       @PathVariable UUID documentId,
                                       @Valid @RequestBody CancellationRequest request) {
    cancellations.acceptCancellation(documentNumber, documentId, mapper.toService(request));
    return AsyncOperationResponse.builder().message("Solicitud de anulación recibida.").documentId(documentId).status("PENDING").build();
  }

  @GetMapping("/{documentId}/cancellation")
  public CancellationApiResponse get(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                     @PathVariable UUID documentId) {
    return mapper.toApi(cancellations.getCancellation(documentNumber, documentId));
  }

  @PostMapping("/{documentId}/cancellation/recover")
  public CancellationApiResponse recover(
          @PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @PathVariable UUID documentId,
          @Valid @RequestBody RecoveryRequest request) {
    return mapper.toApi(cancellations.recoverCancellation(documentNumber, documentId, request.ticket()));
  }

}
