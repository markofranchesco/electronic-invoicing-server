package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.util.Optional;

@Builder
public record SunatStatus(String code, Optional<SunatSendResult> receipt) {
  public boolean hasConsistentSummaryReceipt() {
    return receipt.isPresent() && ("0".equals(code)
            || ("99".equals(code) && !receipt.orElseThrow().accepted()));
  }

  public boolean summaryProcessing() {
    return "98".equals(code) && receipt.isEmpty();
  }

  public boolean summaryRequiresReview() {
    return "99".equals(code) || receipt.isPresent() && !hasConsistentSummaryReceipt();
  }
}
