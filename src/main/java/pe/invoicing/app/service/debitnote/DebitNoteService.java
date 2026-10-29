package pe.invoicing.app.service.debitnote;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.document.DocumentService;
import pe.invoicing.app.service.document.models.*;
import java.util.*;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Service
@RequiredArgsConstructor
public class DebitNoteService {
  private final DocumentService documents;

  public SubmissionResult accept(String ruc, UUID id, String key, DebitNoteDraft draft) {
    return submit(ruc, id, key, draft, true);
  }

  public SubmissionResult issue(String ruc, UUID id, String key, DebitNoteDraft draft) {
    return submit(ruc, id, key, draft, false);
  }

  private SubmissionResult submit(String ruc, UUID id, String key, DebitNoteDraft draft, boolean deferred) {
    if (draft == null || draft.reasonCode() == null || !Set.of("01", "02").contains(draft.reasonCode())
            || draft.reason() == null || draft.reason().isBlank() || draft.reason().length() > 250
            || draft.reason().matches("(?s).*[\\r\\n\\t].*") || draft.issueDate() == null
            || draft.series() == null || !draft.series().matches("[FB][A-Z0-9]{3}"))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Revisa motivo, serie y fecha de la nota de débito.");
    var original = documents.getDocument(ruc, id);
    var items = DebitNoteRules.items(original, draft.charges());
    var details = NoteDetails.builder().reasonCode(draft.reasonCode()).reason(draft.reason())
            .originalDocument(DocumentReference.builder().documentType(original.getDocumentType())
                    .series(original.getSeries()).number(original.getNumber()).build()).build();
    DebitNoteRules.validate(original, original.toBuilder().documentType("08").items(items).note(details)
            .issueDate(draft.issueDate()).build(), List.of());
    var request = DocumentDraft.builder().documentType("08").sourceDocumentId(id).series(draft.series())
            .issueDate(draft.issueDate()).currency(original.getCurrency()).exchangeRate(original.getExchangeRate())
            .customer(original.getCustomer()).establishment(original.getEstablishment()).items(items)
            .operationType("0101").externalReference(draft.externalReference()).note(details).build();
    return deferred ? documents.acceptNewDocument(ruc, key, request) : documents.issueNewDocument(ruc, key, request);
  }
}
