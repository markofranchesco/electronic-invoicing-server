package pe.invoicing.app.service.creditnote;

import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;
import pe.invoicing.app.service.document.models.SubmissionResult;

import java.util.UUID;

public interface CreditNoteService {
  SubmissionResult accept(String documentNumber, UUID documentId, String key, CreditNoteDraft draft);
  SubmissionResult issue(String documentNumber, UUID documentId, String key, CreditNoteDraft draft);
}
