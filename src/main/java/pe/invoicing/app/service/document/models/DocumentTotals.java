package pe.invoicing.app.service.document.models;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Builder(toBuilder = true)
@Value
@JsonDeserialize(builder = DocumentTotals.DocumentTotalsBuilder.class)
public class DocumentTotals {
  private String taxableAmount;
  private String exemptAmount;
  private String unaffectedAmount;
  private String igvAmount;
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private String icbperAmount;
  private String discountAmount;
  private String totalAmount;

  @JsonPOJOBuilder(withPrefix = "")
  public static class DocumentTotalsBuilder {
  }
}
