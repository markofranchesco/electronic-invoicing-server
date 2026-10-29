package pe.invoicing.app.controller.document.dtos.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SummaryReportRequest(@NotBlank @Pattern(regexp = "RC-[0-9]{8}-[0-9]{1,5}") String previousReference) {
}
