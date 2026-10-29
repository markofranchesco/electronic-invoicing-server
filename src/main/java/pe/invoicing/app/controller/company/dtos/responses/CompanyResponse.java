package pe.invoicing.app.controller.company.dtos.responses;

import lombok.Builder;
import lombok.Value;
import pe.invoicing.app.controller.company.dtos.shared.Address;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Value
@Builder
@JsonDeserialize(builder = CompanyResponse.CompanyResponseBuilder.class)
public class CompanyResponse {
  private UUID id;
  private String documentNumber;
  private UUID accountId;
  private String taxpayerType;
  private String legalName;
  private String tradeName;
  private String logoBase64;

  // Periodo de elegibilidad verificado por el contribuyente para la Ley 31556.
  private java.time.LocalDate specialIgvFrom;
  private java.time.LocalDate specialIgvUntil;
  private String email;
  private Address address;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private Boolean taxCredentialsConfigured;
  private Boolean digitalCertificateConfigured;

  @JsonPOJOBuilder(withPrefix = "")
  public static class CompanyResponseBuilder {
  }
}
