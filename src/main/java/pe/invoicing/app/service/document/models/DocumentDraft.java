package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
public record DocumentDraft(
        String documentType,
        String series,
        LocalDate issueDate,
        String currency,
        Customer customer,
        List<DocumentItem> items,
        Payment payment,
        NoteDetails note,
        String operationType,
        String externalReference,
        String observations,
        String exchangeRate,
        UUID sourceDocumentId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String taxRegime,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Establishment establishment,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Detraction detraction) {
  public DocumentDraft {
    items = List.copyOf(items);
  }
}
