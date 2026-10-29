package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.util.UUID;

@Builder
public record IssueOperationData(
        UUID accountId, String documentNumber, String keyHash, String bodyHash, UUID documentId) {
}
