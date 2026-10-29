package pe.invoicing.app.service.session.models;

import lombok.Builder;

import java.util.UUID;

@Builder
public record AuthPrincipal(UUID sessionId, UUID accountId) {
}
