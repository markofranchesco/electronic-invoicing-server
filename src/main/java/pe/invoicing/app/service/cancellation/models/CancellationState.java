package pe.invoicing.app.service.cancellation.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor
public class CancellationState {
  private UUID id;
  private String reference;
  private String previousReference;
  private String reason;
  private UUID documentId;
  private String method;
  private String status;
  private OffsetDateTime requestedAt;
  private OffsetDateTime lastAttemptAt;
  private String sunatCode;
  private String sunatDescription;
  private String ticket;
  private OffsetDateTime nextRetryAt;
  private Boolean xmlAvailable;
  private Boolean cdrAvailable;
  private OffsetDateTime completedAt;
}
