package pe.invoicing.app.service.debitnote;

import java.time.LocalDate;
import java.util.List;

public record DebitNoteDraft(String reasonCode, String reason, String series, LocalDate issueDate,
        String externalReference, List<Charge> charges) {
  /** amount incluye el tributo de la línea original; nunca se recibe el impuesto del cliente. */
  public record Charge(Integer lineNumber, String amount, String description) { }
}
