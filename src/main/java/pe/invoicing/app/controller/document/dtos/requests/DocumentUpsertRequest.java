package pe.invoicing.app.controller.document.dtos.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.document.dtos.shared.CustomerInput;
import pe.invoicing.app.controller.document.dtos.shared.DocumentItem;
import pe.invoicing.app.controller.document.dtos.shared.InstallmentInput;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Value
@Builder
@JsonDeserialize(builder = DocumentUpsertRequest.DocumentUpsertRequestBuilder.class)
public class DocumentUpsertRequest {
  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.SALES_DOCUMENT, message = InputMessages.DOCUMENT_TYPE)
  private String documentType;
  private pe.invoicing.app.service.document.models.Detraction detraction;

  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.SERIES, message = InputMessages.SERIES)
  private String series;

  @NotNull(message = InputMessages.REQUIRED)
  private LocalDate issueDate;

  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.CURRENCY, message = InputMessages.CURRENCY)
  private String currency;

  @Pattern(regexp = "GENERAL|MYPE_HOSPITALITY", message = InputMessages.FORMAT)
  private String taxRegime;

  @Valid
  private pe.invoicing.app.service.document.models.Establishment establishment;

  @Valid
  private CustomerInput customer;

  @NotNull(message = InputMessages.REQUIRED)
  @Size(min = 1, max = 500, message = InputMessages.SIZE)
  private List<@NotNull(message = InputMessages.REQUIRED) @Valid DocumentItem> items;

  @Pattern(regexp = "Contado|Credito", message = InputMessages.FORMAT)
  private String paymentType;

  @Pattern(regexp = InputPatterns.MONEY, message = InputMessages.FORMAT)
  private String pendingAmount;

  private List<@NotNull(message = InputMessages.REQUIRED) @Valid InstallmentInput> installments;

  @NotBlank(message = InputMessages.REQUIRED)
  @Pattern(regexp = InputPatterns.DOMESTIC_OPERATION, message = InputMessages.FORMAT)
  private String operationType;

  @Size(max = 100, message = InputMessages.SIZE)
  private String externalReference;

  @Size(max = 500, message = InputMessages.SIZE)
  private String observations;

  @Pattern(regexp = InputPatterns.DECIMAL, message = InputMessages.FORMAT)
  private String exchangeRate;

  @JsonIgnore
  @AssertTrue(message = InputMessages.INVALID_PAYMENT_AND_CUSTOMER)
  public boolean isValidPaymentAndCustomer() {
    return (!"01".equals(documentType)
            || (Optional.ofNullable(customer).isPresent()
            && Set.of("Contado", "Credito")
            .contains(Optional.ofNullable(paymentType).orElse(""))))
            && ("Credito".equals(paymentType)
            ? Optional.ofNullable(pendingAmount).isPresent()
            && !Optional.ofNullable(installments).orElseGet(List::of).isEmpty()
            : Optional.ofNullable(pendingAmount).isEmpty()
            && Optional.ofNullable(installments).orElseGet(List::of).isEmpty());
  }

  @JsonPOJOBuilder(withPrefix = "")
  public static class DocumentUpsertRequestBuilder {
  }
}
