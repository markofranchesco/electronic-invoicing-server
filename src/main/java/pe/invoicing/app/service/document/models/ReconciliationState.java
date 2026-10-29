package pe.invoicing.app.service.document.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ReconciliationState {
  private int attempts;
  private UUID leaseToken;
  private OffsetDateTime leaseUntil;
  private OffsetDateTime nextAttemptAt;
  private boolean manualReview;
  private String lastError;

  public static boolean pending(DocumentData document) {
    if (Set.of("DRAFT", "UNKNOWN", "SUBMITTED", "PREPARING", "PREPARATION_FAILED", "SEND_FAILED",
            "SUMMARY_PREPARING", "SUMMARY_PREPARATION_FAILED").contains(document.getStatus())
            && (document.getCommunication() == null || !"MANUAL_REVIEW".equals(document.getCommunication().getStatus()))) return true;
    return document.getCancellation() != null && Set.of("PENDING", "PREPARATION_FAILED", "UNKNOWN", "SUBMITTED", "SEND_FAILED")
            .contains(document.getCancellation().getStatus());
  }
}
