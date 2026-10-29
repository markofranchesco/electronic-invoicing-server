package pe.invoicing.app.service.authentication.models;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
public record AccountDetails(
        UUID id, String email, String passwordHash, OffsetDateTime createdAt) {
}
