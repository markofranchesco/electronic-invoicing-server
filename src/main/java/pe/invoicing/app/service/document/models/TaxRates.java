package pe.invoicing.app.service.document.models;

import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.company.models.Company;

import java.time.LocalDate;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

public final class TaxRates {
  private TaxRates() { }

  public static DocumentDraft resolve(DocumentDraft draft, Company company) {
    // Las notas conservan la tasa de la operación original, no la vigente al corregirla.
    if (java.util.Set.of("07", "08").contains(draft.documentType())) return draft;
    String rate = "18.00";
    if ("MYPE_HOSPITALITY".equals(draft.taxRegime())) {
      LocalDate date = draft.issueDate();
      if (company.getSpecialIgvFrom() == null || company.getSpecialIgvUntil() == null
              || date.isBefore(company.getSpecialIgvFrom()) || date.isAfter(company.getSpecialIgvUntil()))
        throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
                "La empresa no tiene elegibilidad verificada para el IGV especial en esta fecha.");
      rate = switch (date.getYear()) {
        case 2025 -> "10.00";
        case 2026 -> "10.50";
        case 2027 -> "15.00";
        default -> throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
                "No hay una tasa especial configurada para esta fecha.");
      };
    }
    String appliedRate = rate;
    return draft.toBuilder().items(draft.items().stream()
            .map(item -> item.toBuilder().igvRate("10".equals(item.getTaxAffectationCode()) ? appliedRate : "0.00")
                    .icbperUnitAmount(bagRate(draft, item)).originalLineId(null).creditAllocation(null).build()).toList()).build();
  }
  private static String bagRate(DocumentDraft draft, DocumentItem item) {
    if (item.getIcbperQuantity() == null) return null;
    if (item.getIcbperQuantity() <= 0 || item.getIcbperQuantity() > 99999
            || new java.math.BigDecimal(item.getQuantity()).compareTo(java.math.BigDecimal.valueOf(item.getIcbperQuantity())) != 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "La cantidad ICBPER debe coincidir con las unidades enteras de bolsas (1–99999).");
    var rate = new java.math.BigDecimal("0.50");
    if (!"PEN".equals(draft.currency())) {
      if (draft.exchangeRate() == null || new java.math.BigDecimal(draft.exchangeRate()).signum() <= 0)
        throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "ICBPER en moneda extranjera requiere tipo de cambio a PEN.");
      rate = rate.divide(new java.math.BigDecimal(draft.exchangeRate()), 5, java.math.RoundingMode.HALF_UP);
    }
    if (rate.signum() <= 0 || rate.compareTo(new java.math.BigDecimal("1000")) >= 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Tipo de cambio fuera del rango para expresar ICBPER.");
    return rate.toPlainString();
  }

}
