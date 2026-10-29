package pe.invoicing.app.controller.creditnote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.creditnote.dtos.CreditNoteIssueRequest;
import pe.invoicing.app.controller.creditnote.mappers.CreditNoteMapper;
import pe.invoicing.app.controller.shared.dtos.responses.AsyncOperationResponse;
import pe.invoicing.app.service.creditnote.CreditNoteService;

import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/companies/{documentNumber}/documents")
public class CreditNoteController {
  private final CreditNoteService creditNotes;
  private final CreditNoteMapper mapper;

  @PostMapping("/{documentId}/credit-notes")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public AsyncOperationResponse issue(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                      @PathVariable UUID documentId,
                                      @RequestHeader("Idempotency-Key") @NotBlank(message = InputMessages.REQUIRED) String key,
                                      @Valid @RequestBody CreditNoteIssueRequest request) {
    return AsyncOperationResponse.builder().message("Solicitud de nota de crédito recibida.").documentId(creditNotes.accept(documentNumber, documentId, key, mapper.toService(request)).getDocumentId()).status("PENDING").build();
  }
}
