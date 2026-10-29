package pe.invoicing.app.service.company.models;

import lombok.Builder;

@Builder
public record SequenceInfo(String documentType, String series, long nextNumber) {
}
