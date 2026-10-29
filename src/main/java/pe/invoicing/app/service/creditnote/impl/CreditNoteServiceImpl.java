package pe.invoicing.app.service.creditnote.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.creditnote.CreditNoteService;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;
import pe.invoicing.app.service.document.DocumentService;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.DocumentReference;
import pe.invoicing.app.service.document.models.NoteDetails;
import pe.invoicing.app.service.document.models.SubmissionResult;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditNoteServiceImpl implements CreditNoteService {
  private final DocumentService documents;

  @Override
  public SubmissionResult issue(
          String documentNumber, UUID documentId, String key, CreditNoteDraft draft) {
    return issueNote(documentNumber, documentId, key, draft, false);
  }

  @Override
  public SubmissionResult accept(String documentNumber, UUID documentId, String key, CreditNoteDraft draft) {
    return issueNote(documentNumber, documentId, key, draft, true);
  }

  private SubmissionResult issueNote(String documentNumber, UUID documentId, String key, CreditNoteDraft draft, boolean deferred) {
    if (draft == null || draft.reasonCode() == null || draft.reason() == null
            || draft.reason().isBlank() || draft.reason().matches("(?s).*[\\r\\n\\t].*")
            || draft.series() == null || draft.issueDate() == null)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Completa los datos de la nota.");
    if (!Set.of("01", "02", "03", "04", "05", "06", "07", "09", "13").contains(draft.reasonCode())) {
      throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              "El motivo requiere un XML de nota de crédito todavía no implementado; no se emitió"
                      + " ningún documento.");
    }
    if (draft.reason().length() > 250 || !draft.series().matches("[FB][A-Z0-9]{3}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revisa el motivo y la serie.");
    }
    DocumentData original = documents.getDocument(documentNumber, documentId);
    if (original.getCancellation() != null && !"REJECTED".equals(original.getCancellation().getStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El comprobante tiene una baja sin resolver.");
    if (draft.issueDate().isBefore(original.getIssueDate()))
      throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              "La nota no puede ser anterior al comprobante original.");
    boolean receipt = "03".equals(original.getDocumentType());
    if (receipt && original.getGrantedAt() == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "La boleta debe haber sido otorgada al adquirente.");
    if (!Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS").contains(original.getStatus())) {
      throw new ResponseStatusException(
              HttpStatus.CONFLICT, "La nota requiere un comprobante aceptado.");
    }
    if (!Set.of("01", "03").contains(original.getDocumentType())
            || !draft.series().startsWith("03".equals(original.getDocumentType()) ? "B" : "F")) {
      throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_CONTENT, "La serie no corresponde al comprobante original.");
    }
    DocumentDraft request = DocumentDraft.builder()
                            .documentType("07")
                            .sourceDocumentId(original.getId())
                            .series(draft.series())
                            .issueDate(draft.issueDate())
                            .currency(original.getCurrency())
                            .establishment(original.getEstablishment())
                            .customer(original.getCustomer())
                            .items(pe.invoicing.app.service.creditnote.models.CreditNoteRules.items(original, draft))
                            .payment(draft.correctedPayment())
                            .operationType("0101")
                            .externalReference(draft.externalReference())
                            .exchangeRate(original.getExchangeRate())
                            .note(
                                    NoteDetails.builder()
                                            .reasonCode(draft.reasonCode())
                                            .reason(draft.reason())
                                            .originalDocument(
                                                    DocumentReference.builder()
                                                            .documentType(original.getDocumentType())
                                                            .series(original.getSeries())
                                                            .number(original.getNumber())
                                                            .build())
                                            .build())
                            .build();
    return deferred ? documents.acceptNewDocument(documentNumber, key, request)
            : documents.issueNewDocument(documentNumber, key, request);
  }
}
