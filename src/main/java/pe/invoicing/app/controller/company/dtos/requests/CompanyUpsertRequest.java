package pe.invoicing.app.controller.company.dtos.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.company.dtos.shared.Address;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = CompanyUpsertRequest.CompanyUpsertRequestBuilder.class)
public class CompanyUpsertRequest {
  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.TAXPAYER_TYPE, message = InputMessages.FORMAT)
  private String taxpayerType;

  @NotBlank(message = InputMessages.REQUIRED)
  @Size(max = 250, message = InputMessages.SIZE)
  private String legalName;

  @Size(max = 255, message = InputMessages.SIZE)
  private String tradeName;
  private String logoBase64;

  // Periodo de elegibilidad verificado por el contribuyente para la Ley 31556.
  private java.time.LocalDate specialIgvFrom;
  private java.time.LocalDate specialIgvUntil;

  @NotBlank(message = InputMessages.REQUIRED)
  @Email(message = InputMessages.EMAIL)
  @Size(max = 254, message = InputMessages.SIZE)
  private String email;

  @NotNull(message = InputMessages.REQUIRED)
  @Valid
  private Address address;

  @NotNull(message = InputMessages.REQUIRED)
  @Valid
  private TaxCredentials taxCredentials;

  @NotNull(message = InputMessages.REQUIRED)
  @Valid
  private DigitalCertificate digitalCertificate;

  @Value
  @Builder
  @JsonDeserialize(builder = TaxCredentials.TaxCredentialsBuilder.class)
  public static class TaxCredentials {
    @NotBlank(message = InputMessages.REQUIRED)
    @Size(max = 30, message = InputMessages.SIZE)
    private String user;

    @NotBlank(message = InputMessages.REQUIRED)
    private String password;

    @JsonPOJOBuilder(withPrefix = "")
    public static class TaxCredentialsBuilder {
    }
  }

  @Value
  @Builder
  @JsonDeserialize(builder = DigitalCertificate.DigitalCertificateBuilder.class)
  public static class DigitalCertificate {
    @NotBlank(message = InputMessages.REQUIRED)
    private String certificate;

    @NotBlank(message = InputMessages.REQUIRED)
    private String password;

    @JsonPOJOBuilder(withPrefix = "")
    public static class DigitalCertificateBuilder {
    }
  }

  @JsonPOJOBuilder(withPrefix = "")
  public static class CompanyUpsertRequestBuilder {
  }
}
