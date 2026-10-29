package pe.invoicing.app.controller.session.dtos.responses;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
public record SessionApiResponse(
        UUID id,
        UUID accountId,
        String ipAddress,
        String userAgent,
        String acceptLanguage,
        String browser,
        String operatingSystem,
        String deviceType,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime revokedAt) {
}
