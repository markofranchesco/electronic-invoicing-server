package pe.invoicing.app.controller.cancellation.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.invoicing.app.config.validation.constants.InputMessages;

public record CancellationRequest(
        @NotNull(message = InputMessages.REQUIRED) Procedure procedure,
        @NotBlank(message = InputMessages.REQUIRED) @Size(max = 250, message = InputMessages.SIZE)
        String reason,
        @NotNull(message = InputMessages.REQUIRED) Boolean notGranted,
        @jakarta.validation.constraints.Pattern(regexp = "(?:RA|RC)-[0-9]{8}-[0-9]{1,5}") String previousReference) {
  public enum Procedure {
    COMMUNICATION_BAJA,
    DAILY_SUMMARY_VOID
  }
}
