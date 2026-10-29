package pe.invoicing.app.service.creditnote.models;

import lombok.Builder;

import java.time.LocalDate;

@Builder
public record CreditNoteDraft(
        String reasonCode,
        String reason,
        String series,
        LocalDate issueDate,
        String externalReference,
        java.util.List<ReturnItem> returnedItems,
        String amount,
        java.util.List<Adjustment> adjustments,
        java.util.List<DescriptionCorrection> descriptions,
        pe.invoicing.app.service.document.models.Payment correctedPayment) {
  public record DescriptionCorrection(Integer lineNumber, String description) { }
  public CreditNoteDraft(String reasonCode, String reason, String series, LocalDate issueDate, String externalReference,
          java.util.List<ReturnItem> returnedItems, String amount, java.util.List<Adjustment> adjustments) {
    this(reasonCode, reason, series, issueDate, externalReference, returnedItems, amount, adjustments, null, null);
  }
  public record Adjustment(Integer lineNumber, String amount) { }
  public CreditNoteDraft(String reasonCode, String reason, String series, LocalDate issueDate, String externalReference, java.util.List<ReturnItem> returnedItems) {
    this(reasonCode, reason, series, issueDate, externalReference, returnedItems, null, null);
  }
  public CreditNoteDraft(String reasonCode, String reason, String series, LocalDate issueDate, String externalReference) {
    this(reasonCode, reason, series, issueDate, externalReference, null, null, null);
  }

  public record ReturnItem(Integer lineNumber, String quantity) {
  }
}
