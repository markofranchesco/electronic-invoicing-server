package pe.invoicing.app.controller.document.dtos.responses;

import lombok.Builder;

@Builder
public record OriginalDocumentReference(String documentType, String series, Long number) {
}
