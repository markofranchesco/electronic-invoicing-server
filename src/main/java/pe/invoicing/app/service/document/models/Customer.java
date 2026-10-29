package pe.invoicing.app.service.document.models;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Builder(toBuilder = true)
@Value
@JsonDeserialize(builder = Customer.CustomerBuilder.class)
public class Customer {
  private String documentType;
  private String documentNumber;
  private String name;
  private String email;
  private String address;

  @JsonPOJOBuilder(withPrefix = "")
  public static class CustomerBuilder {
  }
}
