package pe.invoicing.app.controller.shared.dtos.responses;

import lombok.Builder;

import java.util.UUID;

@Builder
public record AsyncOperationResponse(String message, UUID documentId, String status) {
}
