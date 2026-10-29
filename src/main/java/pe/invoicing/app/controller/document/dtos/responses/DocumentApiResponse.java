package pe.invoicing.app.controller.document.dtos.responses;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
public record DocumentApiResponse(
        UUID id,
        UUID taxpayerId,
        String documentType,
        String series,
        Long number,
        String status,
        String externalReference,
        OffsetDateTime issueDate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String sunatCode,
        String sunatDescription,
        Boolean cdrAvailable,
        Boolean xmlAvailable,
        OriginalDocumentReference referenceDocument,
        String reasonCode,
        String emissionStatus,
        String communicationReference,
        String communicationStatus,
        String cancellationStatus,
        String communicationTicket,
        OffsetDateTime acceptedAt,
        OffsetDateTime grantedAt,
        OffsetDateTime acceptanceDeadlineBase, String acceptanceDateSource,
        pe.invoicing.app.service.document.models.ReconciliationState reconciliation,
        pe.invoicing.app.service.document.models.Establishment establishment) {
  public DocumentApiResponse {
    status =
            switch (status) {
              case "ACCEPTED_WITH_OBSERVATIONS" -> "ACCEPTED";
              case "CANCELLED" -> "ANNULLED";
              case "ACCEPTED", "REJECTED", "SIGNED", "CREATED", "ANNULLED" -> status;
              default -> "PENDING";
            };
  }
}
