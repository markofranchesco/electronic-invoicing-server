package pe.invoicing.app.service.company.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Company {
  private String documentNumber;
  private UUID id;
  private String legalName;
  private String tradeName;
  private String logoBase64;

  // Periodo de elegibilidad verificado por el contribuyente para la Ley 31556.
  private java.time.LocalDate specialIgvFrom;
  private java.time.LocalDate specialIgvUntil;
  private String email;
  private Address address;
  private Boolean ready;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private UUID accountId;
  private String taxpayerType;
  private Boolean taxCredentialsConfigured;
  private Boolean digitalCertificateConfigured;
}
