package pe.invoicing.app.service.company.models;

import lombok.Builder;

@Builder
public record CompanyData(
        String legalName,
        String tradeName,
        String logoBase64,
        String email,
        Address address,
        String certificate,
        String certificatePassword,
        String solUsername,
        String solPassword,
        String taxpayerType,
        java.time.LocalDate specialIgvFrom,
        java.time.LocalDate specialIgvUntil) {
}
