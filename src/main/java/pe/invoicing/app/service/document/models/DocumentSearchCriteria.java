package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.time.LocalDate;

@Builder
public record DocumentSearchCriteria(
        String documentNumber,
        String documentType,
        String status,
        LocalDate from,
        LocalDate to,
        String series,
        Long number,
        String externalReference) {
}
