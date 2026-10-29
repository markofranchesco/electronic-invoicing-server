package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.util.UUID;

@Builder
public record DocumentArtifactData(
        UUID accountId, String documentNumber, UUID documentId, String artifactType, byte[] content) {
}
