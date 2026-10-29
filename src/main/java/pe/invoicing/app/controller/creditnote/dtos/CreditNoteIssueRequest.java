package pe.invoicing.app.controller.creditnote.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;

import java.time.LocalDate;

public record CreditNoteIssueRequest(
        @NotBlank(message = InputMessages.REQUIRED)
        @Pattern(regexp = InputPatterns.TWO_DIGIT_CODE, message = InputMessages.FORMAT)
        String reasonCode,
        @NotBlank(message = InputMessages.REQUIRED) @Size(max = 250, message = InputMessages.SIZE)
        String reason,
        @NotBlank(message = InputMessages.REQUIRED)
        @Pattern(regexp = InputPatterns.SERIES, message = InputMessages.SERIES)
        String series,
        @NotNull(message = InputMessages.REQUIRED) LocalDate issueDate,
        @Size(max = 100, message = InputMessages.SIZE) String externalReference,
        java.util.List<ReturnItem> returnedItems,
        String amount,
        java.util.List<Adjustment> adjustments,
        java.util.List<DescriptionCorrection> descriptions,
        pe.invoicing.app.service.document.models.Payment correctedPayment) {
  public record DescriptionCorrection(Integer lineNumber, String description) { }
  public CreditNoteIssueRequest(String reasonCode, String reason, String series, LocalDate issueDate, String externalReference,
          java.util.List<ReturnItem> returnedItems, String amount, java.util.List<Adjustment> adjustments) {
    this(reasonCode, reason, series, issueDate, externalReference, returnedItems, amount, adjustments, null, null);
  }
  public record Adjustment(Integer lineNumber, String amount) { }
  public CreditNoteIssueRequest(String reasonCode, String reason, String series, LocalDate issueDate, String externalReference, java.util.List<ReturnItem> returnedItems) {
    this(reasonCode, reason, series, issueDate, externalReference, returnedItems, null, null);
  }
  public CreditNoteIssueRequest(String reasonCode, String reason, String series, LocalDate issueDate, String externalReference) {
    this(reasonCode, reason, series, issueDate, externalReference, null, null, null);
  }

  public record ReturnItem(Integer lineNumber, String quantity) {
  }
}
