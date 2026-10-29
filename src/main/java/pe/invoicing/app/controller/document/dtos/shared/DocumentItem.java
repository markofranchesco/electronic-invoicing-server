package pe.invoicing.app.controller.document.dtos.shared;

import jakarta.validation.constraints.DecimalMin;
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

@Value
@Builder
@JsonDeserialize(builder = DocumentItem.DocumentItemBuilder.class)
public class DocumentItem {
  @Size(max = 60, message = InputMessages.SIZE)
  private String productCode;
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private Integer icbperQuantity;

  @NotBlank(message = InputMessages.REQUIRED)
  @Size(max = 500, message = InputMessages.SIZE)
  private String description;

  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.UNIT, message = InputMessages.FORMAT)
  private String unitCode;

  @NotBlank(message = InputMessages.QUANTITY_REQUIRED)
  @Pattern(regexp = InputPatterns.DECIMAL, message = InputMessages.QUANTITY)
  @DecimalMin(value = "0", inclusive = false, message = InputMessages.POSITIVE)
  private String quantity;

  @NotBlank(message = InputMessages.PRICE_REQUIRED)
  @Pattern(regexp = InputPatterns.DECIMAL, message = InputMessages.PRICE)
  @DecimalMin(value = "0", inclusive = false, message = InputMessages.POSITIVE)
  private String unitPrice;

  @NotNull(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.TAX_AFFECTATION, message = InputMessages.TAX_AFFECTATION)
  private String taxAffectationCode;

  @Pattern(regexp = InputPatterns.MONEY, message = InputMessages.DISCOUNT)
  private String discountAmount;

  @JsonPOJOBuilder(withPrefix = "")
  public static class DocumentItemBuilder {
  }
}
