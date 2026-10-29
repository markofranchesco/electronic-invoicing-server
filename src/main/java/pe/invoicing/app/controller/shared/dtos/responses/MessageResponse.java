package pe.invoicing.app.controller.shared.dtos.responses;

import lombok.Builder;

@Builder
public record MessageResponse(String message) {
}
