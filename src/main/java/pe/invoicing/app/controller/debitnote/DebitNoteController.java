package pe.invoicing.app.controller.debitnote;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.shared.dtos.responses.AsyncOperationResponse;
import pe.invoicing.app.service.debitnote.*;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/companies/{documentNumber}/documents")
public class DebitNoteController {
  private final DebitNoteService notes;

  @PostMapping("/{documentId}/debit-notes")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public AsyncOperationResponse issue(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @PathVariable UUID documentId, @RequestHeader("Idempotency-Key") @NotBlank String key,
          @RequestBody DebitNoteDraft request) {
    return AsyncOperationResponse.builder().message("Solicitud de nota de débito recibida.")
            .documentId(notes.accept(documentNumber, documentId, key, request).getDocumentId()).status("PENDING").build();
  }
}
