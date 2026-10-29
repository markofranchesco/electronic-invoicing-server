package pe.invoicing.app.controller.document.dtos.requests;

import jakarta.validation.constraints.Pattern;

public record RecoveryRequest(@Pattern(regexp = "[A-Za-z0-9-]{1,100}") String ticket) {
}
