package pe.invoicing.app.service.session.models;

import lombok.Builder;

@Builder
public record SessionClient(
        String ipAddress,
        String userAgent,
        String acceptLanguage,
        String browser,
        String operatingSystem,
        String deviceType) {
}
