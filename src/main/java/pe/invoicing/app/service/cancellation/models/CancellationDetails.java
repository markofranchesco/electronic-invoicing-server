package pe.invoicing.app.service.cancellation.models;

import lombok.Builder;

@Builder
public record CancellationDetails(String reason, String procedure, Boolean notGranted, String previousReference) {
  public CancellationDetails(String reason, String procedure, Boolean notGranted) {
    this(reason, procedure, notGranted, null);
  }
}
