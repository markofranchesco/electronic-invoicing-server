package pe.invoicing.app.service.document.impl;

import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.service.document.models.DocumentDraft;

import java.util.Set;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

final class DocumentDraftValidator {
  private DocumentDraftValidator() {
  }

  static void validate(DocumentDraft draft) {
    require(draft != null, "Falta el comprobante.");
    require(draft.documentType() != null && Set.of("01", "03", "07", "08").contains(draft.documentType()),
            "Tipo de comprobante no admitido.");
    require(matches(draft.series(), "[FB][A-Z0-9]{3}"), "Serie inválida.");
    require(draft.issueDate() != null, "Falta la fecha de emisión.");
    require(draft.taxRegime() == null || Set.of("GENERAL", "MYPE_HOSPITALITY").contains(draft.taxRegime()),
            "Régimen IGV no admitido.");
    require(matches(draft.currency(), InputPatterns.CURRENCY), "Moneda no admitida.");
    require(draft.operationType() != null && Set.of("0101", "1001").contains(draft.operationType()), "Operación fiscal no implementada.");
    require("1001".equals(draft.operationType()) == (draft.detraction() != null), "La operación 1001 requiere detracción y 0101 no la admite.");
    if (draft.establishment() != null) {
      var local = draft.establishment();
      require(matches(local.code(), "[0-9]{4}"), "Código de establecimiento inválido.");
      require(local.name() == null || text(local.name(), 250), "Nombre de establecimiento inválido.");
      require(local.address() != null, "Falta dirección del establecimiento.");
      var address = local.address();
      require(text(address.getStreet(), 250) && matches(address.getGeographicCode(), "[0-9]{6}")
              && text(address.getDepartment(), 100) && text(address.getProvince(), 100)
              && text(address.getDistrict(), 100) && "PE".equals(address.getCountryCode()),
              "Completa dirección, ubigeo y país PE del establecimiento.");
    }
    require(draft.externalReference() == null || text(draft.externalReference(), 100), "Referencia externa inválida.");
    require(draft.observations() == null || text(draft.observations(), 500), "Observaciones inválidas.");
    require(draft.customer() != null, "Falta el adquirente.");
    var customer = draft.customer();
    require(text(customer.getName(), 250), "Nombre del adquirente inválido.");
    String identity = switch (customer.getDocumentType() == null ? "" : customer.getDocumentType()) {
      case "6" -> "[0-9]{11}";
      case "1" -> "[0-9]{8}";
      case "0" -> "0";
      case "4", "7" -> "[A-Za-z0-9]{1,12}";
      default -> "(?!)";
    };
    require(matches(customer.getDocumentNumber(), identity), "Identificación del adquirente inválida.");
    require(draft.items() != null && !draft.items().isEmpty() && draft.items().size() <= 500,
            "Detalle vacío o superior a 500 líneas.");
    for (var item : draft.items()) {
      require(item != null, "Línea de detalle vacía.");
      require(item.getProductCode() == null || text(item.getProductCode(), 60), "Código de producto inválido.");
      require(text(item.getDescription(), 500), "Descripción de ítem inválida.");
      require(matches(item.getUnitCode(), InputPatterns.UNIT), "Unidad inválida.");
      require(matches(item.getQuantity(), InputPatterns.DECIMAL), "Cantidad inválida.");
      require(matches(item.getUnitPrice(), InputPatterns.DECIMAL), "Precio inválido.");
      require(item.getDiscountAmount() == null || matches(item.getDiscountAmount(), InputPatterns.MONEY),
              "Descuento inválido.");
      require(matches(item.getTaxAffectationCode(), InputPatterns.TAX_AFFECTATION), "Afectación no admitida.");
    }
    require(draft.exchangeRate() == null || matches(draft.exchangeRate(), InputPatterns.DECIMAL),
            "Tipo de cambio inválido.");
    if (draft.payment() != null) {
      var payment = draft.payment();
      require(matches(payment.getType(), "CASH|CREDIT"), "Forma de pago inválida.");
      if ("CREDIT".equals(payment.getType())) {
        require(matches(payment.getPendingAmount(), InputPatterns.MONEY), "Saldo pendiente inválido.");
        require(payment.getInstallments() != null && !payment.getInstallments().isEmpty() && payment.getInstallments().size() <= 999, "Faltan cuotas.");
        for (var installment : payment.getInstallments()) {
          require(installment != null && installment.getDueDate() != null, "Vencimiento de cuota inválido.");
          require(matches(installment.getAmount(), InputPatterns.MONEY), "Importe de cuota inválido.");
        }
      }
    }
    if (Set.of("07", "08").contains(draft.documentType())) {
      require(draft.note() != null && draft.note().getOriginalDocument() != null
              && draft.sourceDocumentId() != null, "Falta el comprobante original de la nota.");
      var note = draft.note();
      var reference = note.getOriginalDocument();
      require(matches(note.getReasonCode(), "08".equals(draft.documentType()) ? "01|02" : "01|02|03|04|05|06|07|09|13") && text(note.getReason(), 250), "Motivo de nota no admitido.");
      require(matches(reference.getDocumentType(), "01|03")
                      && matches(reference.getSeries(), "[FB][A-Z0-9]{3}")
                      && reference.getNumber() != null && reference.getNumber() > 0 && reference.getNumber() <= 99999999,
              "Referencia de nota inválida.");
    } else {
      require(draft.note() == null && draft.sourceDocumentId() == null, "La venta no admite referencia de nota.");
    }
  }

  private static boolean matches(String value, String pattern) {
    return value != null && value.matches(pattern);
  }

  private static boolean text(String value, int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum
            && !value.matches("(?s).*[\\r\\n\\t].*");
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new ResponseStatusException(UNPROCESSABLE_CONTENT, message);
  }
}
