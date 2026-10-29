package pe.invoicing.app.service.session.models;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
public record TokenDetails(
        UUID id,
        UUID accountId,
        String hash,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        SessionClient client) {
}
