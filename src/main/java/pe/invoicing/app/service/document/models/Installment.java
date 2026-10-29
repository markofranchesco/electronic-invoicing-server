package pe.invoicing.app.service.document.models;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.LocalDate;

@Builder(toBuilder = true)
@Value
@JsonDeserialize(builder = Installment.InstallmentBuilder.class)
public class Installment {
  private LocalDate dueDate;
  private String amount;

  @JsonPOJOBuilder(withPrefix = "")
  public static class InstallmentBuilder {
  }
}
