package pe.invoicing.app.service.debitnote;

import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.document.models.*;
import java.math.BigDecimal;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

public final class DebitNoteRules {
  private DebitNoteRules() { }

  public static List<DocumentItem> items(DocumentData original, List<DebitNoteDraft.Charge> charges) {
    require(charges != null && !charges.isEmpty() && charges.size() <= 500, "Indica los cargos de la nota.");
    Set<Integer> seen = new HashSet<>();
    List<DocumentItem> items = new ArrayList<>();
    for (var charge : charges) {
      require(charge != null && charge.lineNumber() != null && charge.lineNumber() > 0
              && charge.lineNumber() <= original.getItems().size() && seen.add(charge.lineNumber()), "Línea original inválida o repetida.");
      require(charge.amount() != null && charge.amount().matches("[0-9]{1,12}(\\.[0-9]{1,2})?")
              && new BigDecimal(charge.amount()).signum() > 0, "El cargo debe ser positivo y tener hasta dos decimales.");
      var source = original.getItems().get(charge.lineNumber() - 1);
      items.add(source.toBuilder().originalLineId(charge.lineNumber()).quantity("1").unitPrice(charge.amount())
              .discountAmount("0.00").icbperQuantity(null).icbperUnitAmount(null).creditAllocation(null)
              .description(charge.description() == null ? source.getDescription() : charge.description()).build());
    }
    return List.copyOf(items);
  }

  /** Revalidación dentro del bloqueo del emisor, antes de reservar el correlativo. */
  public static void validate(DocumentData original, DocumentData note, List<DocumentData> previous) {
    require("08".equals(note.getDocumentType()) && note.getNote() != null
            && Set.of("01", "02").contains(note.getNote().getReasonCode()), "Motivo de débito no admitido.");
    require(Set.of("01", "03").contains(original.getDocumentType()), "El origen debe ser factura o boleta.");
    if (!Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS").contains(original.getStatus())
            || original.getGrantedAt() == null
            || original.getCancellation() != null && !"REJECTED".equals(original.getCancellation().getStatus()))
      throw new ResponseStatusException(CONFLICT, "La nota requiere una venta aceptada, otorgada y vigente.");
    var reference = note.getNote().getOriginalDocument();
    require(reference != null && Objects.equals(reference.getDocumentType(), original.getDocumentType())
            && Objects.equals(reference.getSeries(), original.getSeries()) && Objects.equals(reference.getNumber(), original.getNumber())
            && Objects.equals(note.getCustomer(), original.getCustomer()) && Objects.equals(note.getCurrency(), original.getCurrency())
            && Objects.equals(note.getExchangeRate(), original.getExchangeRate()) && !note.getIssueDate().isBefore(original.getIssueDate()),
            "La nota no corresponde al comprobante original.");
    if (previous.stream().anyMatch(d -> "07".equals(d.getDocumentType())
            && !Set.of("REJECTED", "CANCELLED").contains(d.getStatus())
            && d.getNote() != null && Set.of("01", "02", "06").contains(d.getNote().getReasonCode())))
      throw new ResponseStatusException(CONFLICT, "La venta ya tiene una nota de anulación o devolución total.");
    require(note.getItems() != null && !note.getItems().isEmpty(), "Faltan cargos.");
    var charges = note.getItems().stream().map(i -> new DebitNoteDraft.Charge(i.getOriginalLineId(), i.getUnitPrice(), i.getDescription())).toList();
    require(items(original, charges).equals(note.getItems()), "Los cargos deben conservar los tributos de la venta original.");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(UNPROCESSABLE_CONTENT, message);
  }
}
