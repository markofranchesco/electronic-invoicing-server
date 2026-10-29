package pe.invoicing.app.service.authentication.models;

import lombok.Builder;

@Builder
public record Login(String email, String password) {
}
