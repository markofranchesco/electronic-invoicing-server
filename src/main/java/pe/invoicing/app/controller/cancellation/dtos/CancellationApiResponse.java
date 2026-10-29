package pe.invoicing.app.controller.cancellation.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CancellationApiResponse(String sunatCode, String sunatDescription,
                                      java.util.UUID id, java.util.UUID documentId, String reference, String status,
                                      String method, String ticket, Boolean xmlAvailable, Boolean cdrAvailable,
                                      java.time.OffsetDateTime requestedAt, java.time.OffsetDateTime completedAt,
                                      java.time.OffsetDateTime nextRetryAt) {
}
