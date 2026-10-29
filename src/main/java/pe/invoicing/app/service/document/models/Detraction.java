package pe.invoicing.app.service.document.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

/** Declaración expresa del emisor; no se infiere la sujeción por el nombre del negocio. */
public record Detraction(String serviceCode, String bankAccount) {
  private static final Map<String, String> RATES = Map.of("012","12", "019","10", "020","12",
          "021","10", "022","12", "024","10", "025","10", "026","10", "030","4", "037","12");
  public String rate() {
    if (serviceCode == null || !RATES.containsKey(serviceCode))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Código SPOT de servicios no admitido.");
    return RATES.get(serviceCode);
  }
  public BigDecimal amountPen(String total, String currency, String exchangeRate) {
    return new BigDecimal(total).multiply(exchange(currency, exchangeRate)).multiply(new BigDecimal(rate()))
            .movePointLeft(2).setScale(0, RoundingMode.HALF_UP).setScale(2);
  }
  public BigDecimal amountInCurrency(String total, String currency, String exchangeRate) {
    return amountPen(total, currency, exchangeRate).divide(exchange(currency, exchangeRate), 2, RoundingMode.HALF_UP);
  }
  private BigDecimal exchange(String currency, String rate) {
    if ("PEN".equals(currency)) return BigDecimal.ONE;
    if (rate == null || new BigDecimal(rate).signum() <= 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Detracción requiere tipo de cambio a PEN.");
    return new BigDecimal(rate);
  }
  public void validate(DocumentDraft draft, DocumentTotals totals) {
    rate();
    if (!"01".equals(draft.documentType()) || !"1001".equals(draft.operationType())
            || bankAccount == null || !bankAccount.matches("[0-9]{11}")
            || draft.items().stream().anyMatch(i -> !"10".equals(i.getTaxAffectationCode()) || i.getIcbperQuantity() != null))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "SPOT requiere factura de servicios gravados, operación 1001 y cuenta BN de 11 dígitos.");
    if (new BigDecimal(totals.getTotalAmount()).multiply(exchange(draft.currency(), draft.exchangeRate())).compareTo(new BigDecimal("700")) <= 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "El servicio no supera S/700 para esta operación SPOT.");
    if (draft.payment() != null && "CREDIT".equals(draft.payment().getType())
            && new BigDecimal(draft.payment().getPendingAmount()).compareTo(new BigDecimal(totals.getTotalAmount())
                    .subtract(amountInCurrency(totals.getTotalAmount(), draft.currency(), draft.exchangeRate()))) > 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "El saldo a crédito debe excluir la detracción.");
  }
}
