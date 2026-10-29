package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.util.UUID;

@Builder
public record IssueOperation(String bodyHash, UUID documentId) {
}
