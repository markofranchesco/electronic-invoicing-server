package pe.invoicing.app.controller.document.dtos.shared;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Optional;

@Value
@Builder
@JsonDeserialize(builder = CustomerInput.CustomerInputBuilder.class)
public class CustomerInput {
  @NotNull(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.IDENTITY_DOCUMENT_TYPE, message = InputMessages.FORMAT)
  private String documentType;

  @NotBlank(message = InputMessages.REQUIRED)
  @Size(max = 20, message = InputMessages.SIZE)
  private String documentNumber;

  @NotBlank(message = InputMessages.REQUIRED)
  @Size(max = 250, message = InputMessages.SIZE)
  private String name;

  private String email;

  @Size(max = 250, message = InputMessages.SIZE)
  private String address;

  @JsonIgnore
  @AssertTrue(message = InputMessages.IDENTITY)
  public boolean isValidIdentity() {
    return Optional.ofNullable(documentNumber)
            .filter(
                    number ->
                            switch (Optional.ofNullable(documentType).orElse("")) {
                              case "6" -> number.matches("[0-9]{11}");
                              case "1" -> number.matches("[0-9]{8}");
                              case "0" -> "0".equals(number);
                              case "4", "7" -> number.matches("[A-Za-z0-9]{1,12}");
                              default -> false;
                            })
            .isPresent();
  }

  @JsonPOJOBuilder(withPrefix = "")
  public static class CustomerInputBuilder {
  }
}
