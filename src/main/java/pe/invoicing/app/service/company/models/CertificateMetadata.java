package pe.invoicing.app.service.company.models;

import lombok.Builder;

import java.time.OffsetDateTime;

@Builder
public record CertificateMetadata(boolean valid, String subject, OffsetDateTime expiresAt) {
}
