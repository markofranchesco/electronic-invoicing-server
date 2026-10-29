package pe.invoicing.app.service.document.models;

public record CommunicationRecord(String reference, String kind, String status, String code, String description,
                                  String ticket, java.time.OffsetDateTime attemptedAt,
                                  java.time.OffsetDateTime completedAt) {
}
