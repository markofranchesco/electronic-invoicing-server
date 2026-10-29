package pe.invoicing.app.service.document.models;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;

@Builder(toBuilder = true)
@Value
@JsonDeserialize(builder = Payment.PaymentBuilder.class)
public class Payment {
  private String type;
  private String pendingAmount;
  private List<Installment> installments;

  @JsonPOJOBuilder(withPrefix = "")
  public static class PaymentBuilder {
  }
}
