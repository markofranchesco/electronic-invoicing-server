package pe.invoicing.app.service.document.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor
public class CommunicationState {
  private String status;
  private String method;
  private String ticket;
  private String condition;
  private String previousReference;
  private String previousDocumentStatus;
  private String responseCode;
  private String responseDescription;
  private OffsetDateTime lastAttemptAt;
  private OffsetDateTime firstAttemptAt;
  private String batchReference;
  private OffsetDateTime confirmedAt;
}
