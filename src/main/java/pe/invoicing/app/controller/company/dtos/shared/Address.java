package pe.invoicing.app.controller.company.dtos.shared;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = Address.AddressBuilder.class)
public class Address {
  @NotBlank(message = InputMessages.REQUIRED)
  @Size(min = 1, max = 250, message = InputMessages.ADDRESS_LENGTH)
  private String street;

  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.GEOGRAPHIC_CODE, message = InputMessages.FORMAT)
  private String geographicCode;

  @NotBlank(message = InputMessages.REQUIRED)
  @Size(max = 100, message = InputMessages.SIZE)
  private String department;

  @NotBlank(message = InputMessages.REQUIRED)
  @Size(max = 100, message = InputMessages.SIZE)
  private String province;

  @NotBlank(message = InputMessages.REQUIRED)
  @Size(max = 100, message = InputMessages.SIZE)
  private String district;

  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.COUNTRY, message = InputMessages.FORMAT)
  private String countryCode;

  @JsonPOJOBuilder(withPrefix = "")
  public static class AddressBuilder {
  }
}
