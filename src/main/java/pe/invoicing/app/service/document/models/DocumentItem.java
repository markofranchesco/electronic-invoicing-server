package pe.invoicing.app.service.document.models;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Builder(toBuilder = true)
@Value
@JsonDeserialize(builder = DocumentItem.DocumentItemBuilder.class)
public class DocumentItem {
  private String productCode;
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private Integer icbperQuantity;
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private String icbperUnitAmount;
  private String description;
  private String unitCode;
  private String quantity;
  private String unitPrice;
  private String taxAffectationCode;
  private String discountAmount;
  // Instantánea calculada al emitir; los documentos anteriores conservan el 18%.
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private String igvRate;
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private Integer originalLineId;

  // Distribución calculada bajo el bloqueo de la venta original para devoluciones parciales.
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private CreditAllocation creditAllocation;

  public record CreditAllocation(String base, String tax, String total,
          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String icbper) {
    public CreditAllocation(String base, String tax, String total) { this(base, tax, total, null); }
  }

  @JsonPOJOBuilder(withPrefix = "")
  public static class DocumentItemBuilder {
  }
}
