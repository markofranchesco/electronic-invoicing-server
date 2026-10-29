package pe.invoicing.app.service.authentication.models;

import lombok.Builder;

@Builder
public record Registration(String email, String password) {
}
