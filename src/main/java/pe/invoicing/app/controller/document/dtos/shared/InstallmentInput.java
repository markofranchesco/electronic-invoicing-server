package pe.invoicing.app.controller.document.dtos.shared;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Value;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.LocalDate;

@Value
@Builder
@JsonDeserialize(builder = InstallmentInput.InstallmentInputBuilder.class)
public class InstallmentInput {
  @NotNull(message = InputMessages.REQUIRED)
  private LocalDate dueDate;

  @NotBlank(message = InputMessages.INSTALLMENT_REQUIRED)
  @Pattern(regexp = InputPatterns.MONEY, message = InputMessages.INSTALLMENT_AMOUNT)
  @DecimalMin(value = "0", inclusive = false, message = InputMessages.POSITIVE)
  private String amount;

  @JsonPOJOBuilder(withPrefix = "")
  public static class InstallmentInputBuilder {
  }
}
