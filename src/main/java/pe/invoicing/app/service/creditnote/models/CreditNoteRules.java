package pe.invoicing.app.service.creditnote.models;

import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentItem;
import pe.invoicing.app.service.document.models.LineAmounts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

public final class CreditNoteRules {
  private CreditNoteRules() { }

  public static List<DocumentItem> items(DocumentData original, CreditNoteDraft draft) {
    if (Set.of("03", "13").contains(draft.reasonCode())) return informationalItems(original, draft);
    require(draft.descriptions() == null && draft.correctedPayment() == null, "El motivo no admite correcciones informativas.");
    if (Set.of("04", "05", "09").contains(draft.reasonCode())) return adjustedItems(original, draft);
    require(draft.amount() == null && (draft.adjustments() == null || draft.adjustments().isEmpty()), "El motivo no admite ajustes monetarios.");
    if (!"07".equals(draft.reasonCode())) {
      require(draft.returnedItems() == null || draft.returnedItems().isEmpty(), "Una nota total no admite selección de líneas.");
      return original.getItems();
    }
    require(draft.returnedItems() != null && !draft.returnedItems().isEmpty() && draft.returnedItems().size() <= 500, "Selecciona las cantidades a devolver.");
    Set<Integer> selected = new HashSet<>();
    List<DocumentItem> result = new ArrayList<>();
    for (var returned : draft.returnedItems()) {
      require(returned != null && returned.lineNumber() != null
              && returned.lineNumber() >= 1 && returned.lineNumber() <= original.getItems().size(),
              "Ítem original inválido.");
      require(selected.add(returned.lineNumber()), "No repitas el ítem original.");
      require(returned.quantity() != null && returned.quantity().matches("[0-9]{1,12}(\\.[0-9]{1,6})?"),
              "Cantidad de devolución inválida.");
      DocumentItem item = original.getItems().get(returned.lineNumber() - 1);
      BigDecimal quantity = new BigDecimal(returned.quantity());
      BigDecimal originalQuantity = new BigDecimal(item.getQuantity());
      require(quantity.signum() > 0 && quantity.compareTo(originalQuantity) <= 0,
              "La devolución excede la cantidad vendida.");
      BigDecimal discount = new BigDecimal(item.getDiscountAmount() == null ? "0" : item.getDiscountAmount())
              .multiply(quantity).divide(originalQuantity, 2, RoundingMode.HALF_UP);
      result.add(item.toBuilder().originalLineId(returned.lineNumber()).quantity(returned.quantity())
              .discountAmount(discount.toPlainString())
              .icbperQuantity(item.getIcbperQuantity() == null ? null : bagQuantity(quantity)).build());
    }
    return List.copyOf(result);
  }

  // Se ejecuta dentro del bloqueo transaccional del emisor: las notas pendientes también reservan saldo.
  public static void validateBalance(DocumentData original, DocumentData note, List<DocumentData> previous) {
    require("07".equals(note.getDocumentType()) && note.getNote() != null, "Tipo de nota inválido.");
    require(Set.of("01", "03").contains(original.getDocumentType()), "El origen debe ser factura o boleta.");
    boolean receipt = "03".equals(original.getDocumentType());
    if (original.getCancellation() != null && !"REJECTED".equals(original.getCancellation().getStatus())
            || receipt && original.getGrantedAt() == null
            || !Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS").contains(original.getStatus()))
      throw new ResponseStatusException(CONFLICT, "El comprobante original no permite una nota de crédito.");
    var reference = note.getNote().getOriginalDocument();
    require(reference != null && Objects.equals(reference.getDocumentType(), original.getDocumentType())
            && Objects.equals(reference.getSeries(), original.getSeries())
            && Objects.equals(reference.getNumber(), original.getNumber())
            && Objects.equals(note.getCurrency(), original.getCurrency())
            && Objects.equals(note.getCustomer(), original.getCustomer())
            && Objects.equals(note.getExchangeRate(), original.getExchangeRate())
            && !note.getIssueDate().isBefore(original.getIssueDate()), "La nota no corresponde al comprobante original.");
    if (Set.of("03", "13").contains(note.getNote().getReasonCode())) {
      validateInformational(original, note, previous);
      return;
    }
    List<DocumentData> active = previous.stream().filter(d -> "07".equals(d.getDocumentType()))
            .filter(d -> d.getNote() == null || !Set.of("03", "13").contains(d.getNote().getReasonCode()))
            .filter(d -> !Set.of("REJECTED", "CANCELLED").contains(d.getStatus())).toList();
    if (Set.of("04", "05", "09").contains(note.getNote().getReasonCode())) {
      validateAdjustments(original, note, active);
      return;
    }
    if (!"07".equals(note.getNote().getReasonCode())) {
      require(Set.of("01", "02", "06").contains(note.getNote().getReasonCode())
              && note.getItems().equals(original.getItems()), "La nota total debe conservar el detalle original.");
      if (!active.isEmpty()) throw new ResponseStatusException(CONFLICT, "Ya existen notas que reservan saldo del comprobante.");
      return;
    }
    if (active.stream().anyMatch(d -> d.getNote() == null || Set.of("01", "02", "06").contains(d.getNote().getReasonCode())))
      throw new ResponseStatusException(CONFLICT, "Una nota total ya reserva el comprobante.");
    // Revalidar la selección protege también las llamadas internas al servicio de emisión.
    var selection = note.getItems().stream()
            .map(i -> new CreditNoteDraft.ReturnItem(i.getOriginalLineId(), i.getQuantity())).toList();
    require(items(original, new CreditNoteDraft("07", "", "", note.getIssueDate(), null, selection))
            .equals(note.getItems().stream().map(i -> i.toBuilder().creditAllocation(null).build()).toList()),
            "La nota parcial debe conservar precios, descuentos y tributos originales.");
    allocateRounding(original, note, active);
    List<DocumentItem> reserved = new ArrayList<>(note.getItems());
    active.forEach(d -> reserved.addAll(d.getItems()));
    for (int index = 0; index < original.getItems().size(); index++) {
      int id = index + 1;
      DocumentItem source = original.getItems().get(index);
      List<DocumentItem> lines = reserved.stream().filter(i -> Objects.equals(id, i.getOriginalLineId())).toList();
      BigDecimal quantity = java.util.stream.Stream.concat(note.getItems().stream(), active.stream()
              .filter(n -> "07".equals(n.getNote().getReasonCode())).flatMap(n -> n.getItems().stream()))
              .filter(i -> Objects.equals(id, i.getOriginalLineId())).map(i -> new BigDecimal(i.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal amount = lines.stream().map(LineAmounts::calculateLineAmounts).map(LineAmounts::total)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal tax = lines.stream().map(LineAmounts::calculateLineAmounts).map(LineAmounts::tax)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal base = lines.stream().map(LineAmounts::calculateLineAmounts).map(LineAmounts::base)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (tax.compareTo(LineAmounts.calculateLineAmounts(source).tax()) > 0
              || base.compareTo(LineAmounts.calculateLineAmounts(source).base()) > 0
              || quantity.compareTo(new BigDecimal(source.getQuantity())) > 0
              || amount.compareTo(LineAmounts.calculateLineAmounts(source).total()) > 0)
        throw new ResponseStatusException(CONFLICT, "Las notas acumuladas exceden la cantidad o importe del ítem original.");
    }
  }

  private static List<DocumentItem> informationalItems(DocumentData original, CreditNoteDraft draft) {
    require(draft.amount() == null && draft.adjustments() == null && draft.returnedItems() == null,
            "La corrección informativa no devuelve dinero ni cantidades.");
    List<DocumentItem> result = new ArrayList<>();
    if ("03".equals(draft.reasonCode())) {
      require(draft.correctedPayment() == null && draft.descriptions() != null && !draft.descriptions().isEmpty()
              && draft.descriptions().size() <= 500, "Indica las descripciones que corriges.");
      Set<Integer> seen = new HashSet<>();
      for (var correction : draft.descriptions()) {
        require(correction != null && correction.lineNumber() != null && correction.lineNumber() > 0
                && correction.lineNumber() <= original.getItems().size() && seen.add(correction.lineNumber()), "Línea inválida o repetida.");
        require(correction.description() != null && !correction.description().isBlank() && correction.description().length() <= 500
                && !correction.description().matches("(?s).*[\\r\\n\\t].*"), "Descripción corregida inválida.");
        result.add(zeroLine(original.getItems().get(correction.lineNumber()-1), correction.lineNumber()).toBuilder()
                .description(correction.description()).build());
      }
    } else {
      require(draft.descriptions() == null && "01".equals(original.getDocumentType()) && original.getPayment() != null
              && "CREDIT".equals(original.getPayment().getType()), "El motivo 13 requiere una factura al crédito.");
      var payment = draft.correctedPayment();
      require(payment != null && "CREDIT".equals(payment.getType()) && payment.getInstallments() != null
              && !payment.getInstallments().isEmpty() && payment.getInstallments().size() <= 999, "Indica saldo y cuotas corregidos.");
      BigDecimal pending = positiveMoney(payment.getPendingAmount());
      BigDecimal maximum = new BigDecimal(original.getTotals().getTotalAmount());
      if (original.getDetraction() != null) maximum = maximum.subtract(original.getDetraction().amountInCurrency(
              original.getTotals().getTotalAmount(), original.getCurrency(), original.getExchangeRate()));
      require(pending.compareTo(maximum) <= 0, "El saldo corregido excede la factura neta de detracción.");
      BigDecimal sum = BigDecimal.ZERO;
      for (var installment : payment.getInstallments()) {
        require(installment != null && installment.getDueDate() != null && installment.getDueDate().isAfter(original.getIssueDate()), "Cuota anterior a la factura.");
        sum = sum.add(positiveMoney(installment.getAmount()));
      }
      require(sum.compareTo(pending) == 0, "La suma de cuotas no coincide con el saldo corregido.");
      result.add(zeroLine(original.getItems().getFirst(), 1));
    }
    return List.copyOf(result);
  }

  private static DocumentItem zeroLine(DocumentItem original, int number) {
    return original.toBuilder().originalLineId(number).quantity("1").discountAmount("0.00")
            .icbperQuantity(null).icbperUnitAmount(null).creditAllocation(new DocumentItem.CreditAllocation("0.00", "0.00", "0.00")).build();
  }

  private static void validateInformational(DocumentData original, DocumentData note, List<DocumentData> previous) {
    var descriptions = "03".equals(note.getNote().getReasonCode()) ? note.getItems().stream()
            .map(i -> new CreditNoteDraft.DescriptionCorrection(i.getOriginalLineId(), i.getDescription())).toList() : null;
    var request = new CreditNoteDraft(note.getNote().getReasonCode(), note.getNote().getReason(), note.getSeries(), note.getIssueDate(),
            null, null, null, null, descriptions, note.getPayment());
    require(informationalItems(original, request).equals(note.getItems()), "La nota informativa no puede alterar importes ni impuestos.");
    if (previous.stream().anyMatch(n -> "07".equals(n.getDocumentType()) && !Set.of("REJECTED", "CANCELLED").contains(n.getStatus())
            && n.getNote() != null && Set.of("01", "02", "06").contains(n.getNote().getReasonCode())))
      throw new ResponseStatusException(CONFLICT, "El comprobante ya tiene una nota total.");
    if ("13".equals(note.getNote().getReasonCode())) {
      if (original.getCollections() != null && !original.getCollections().isEmpty())
        throw new ResponseStatusException(CONFLICT, "La corrección de cuotas debe resolverse antes de registrar cobranzas.");
      if (previous.stream().anyMatch(n -> "07".equals(n.getDocumentType()) && n.getNote() != null
              && "13".equals(n.getNote().getReasonCode()) && !Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "REJECTED", "CANCELLED").contains(n.getStatus())))
        throw new ResponseStatusException(CONFLICT, "Resuelve la corrección de cuotas pendiente antes de emitir otra.");
    }
  }

  private static List<DocumentItem> adjustedItems(DocumentData original, CreditNoteDraft draft) {
    require(draft.returnedItems() == null || draft.returnedItems().isEmpty(), "Un ajuste monetario no devuelve cantidades.");
    require(!Set.of("04", "05").contains(draft.reasonCode()) || "01".equals(original.getDocumentType()),
            "Los descuentos posteriores 04/05 no se emiten sobre boletas a consumidor final.");
    List<CreditNoteDraft.Adjustment> adjustments;
    if ("04".equals(draft.reasonCode())) {
      require(draft.adjustments() == null || draft.adjustments().isEmpty(), "El descuento global sólo recibe amount.");
      BigDecimal amount = positiveMoney(draft.amount());
      BigDecimal total = new BigDecimal(original.getTotals().getTotalAmount());
      require(amount.compareTo(total) <= 0, "El descuento excede la venta.");
      adjustments = new ArrayList<>();
      BigDecimal distributed = BigDecimal.ZERO;
      BigDecimal cumulative = BigDecimal.ZERO;
      for (int i = 0; i < original.getItems().size(); i++) {
        cumulative = cumulative.add(LineAmounts.calculateLineAmounts(original.getItems().get(i)).total());
        BigDecimal target = amount.multiply(cumulative).divide(total, 2, RoundingMode.HALF_UP);
        BigDecimal share = target.subtract(distributed);
        distributed = target;
        if (share.signum() > 0) adjustments.add(new CreditNoteDraft.Adjustment(i + 1, share.toPlainString()));
      }
    } else {
      require(draft.amount() == null && draft.adjustments() != null && !draft.adjustments().isEmpty()
              && draft.adjustments().size() <= 500, "Selecciona los importes de las líneas a ajustar.");
      adjustments = draft.adjustments();
    }
    List<DocumentItem> result = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    for (var adjustment : adjustments) {
      require(adjustment != null && adjustment.lineNumber() != null && adjustment.lineNumber() > 0
              && adjustment.lineNumber() <= original.getItems().size() && seen.add(adjustment.lineNumber()), "Línea inválida o repetida.");
      var source = original.getItems().get(adjustment.lineNumber() - 1);
      BigDecimal amount = positiveMoney(adjustment.amount());
      require(amount.compareTo(LineAmounts.calculateLineAmounts(source).total()) <= 0, "El ajuste excede el importe original.");
      result.add(source.toBuilder().originalLineId(adjustment.lineNumber()).quantity("1")
              .unitPrice(amount.toPlainString()).discountAmount("0.00").icbperQuantity(null).icbperUnitAmount(null).creditAllocation(null).build());
    }
    return List.copyOf(result);
  }

  private static BigDecimal positiveMoney(String amount) {
    require(amount != null && amount.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")
            && new BigDecimal(amount).signum() > 0, "Importe positivo con hasta dos decimales requerido.");
    return new BigDecimal(amount);
  }

  private static void validateAdjustments(DocumentData original, DocumentData note, List<DocumentData> active) {
    if (active.stream().anyMatch(d -> d.getNote() == null || Set.of("01", "02", "06").contains(d.getNote().getReasonCode())))
      throw new ResponseStatusException(CONFLICT, "Una nota total ya reserva el comprobante.");
    var adjustments = note.getItems().stream().map(i -> new CreditNoteDraft.Adjustment(i.getOriginalLineId(), i.getUnitPrice())).toList();
    var request = new CreditNoteDraft("04".equals(note.getNote().getReasonCode()) ? "05" : note.getNote().getReasonCode(),
            "", "", note.getIssueDate(), null, null, null, adjustments);
    require(adjustedItems(original, request).equals(note.getItems()), "El ajuste debe conservar el tributo y detalle original.");
    for (var item : note.getItems()) {
      var originalAmount = LineAmounts.calculateLineAmounts(original.getItems().get(item.getOriginalLineId()-1));
      var current = LineAmounts.calculateLineAmounts(item);
      var previous = active.stream().flatMap(n -> n.getItems().stream()).filter(i -> Objects.equals(i.getOriginalLineId(), item.getOriginalLineId()))
              .map(LineAmounts::calculateLineAmounts).toList();
      if (previous.stream().map(LineAmounts::total).reduce(current.total(), BigDecimal::add).compareTo(originalAmount.total()) > 0
              || previous.stream().map(LineAmounts::base).reduce(current.base(), BigDecimal::add).compareTo(originalAmount.base()) > 0
              || previous.stream().map(LineAmounts::tax).reduce(current.tax(), BigDecimal::add).compareTo(originalAmount.tax()) > 0)
        throw new ResponseStatusException(CONFLICT, "Los ajustes acumulados exceden el importe o tributo original.");
    }
  }

  private static void allocateRounding(DocumentData original, DocumentData note, List<DocumentData> active) {
    List<DocumentItem> allocated = new ArrayList<>();
    for (var item : note.getItems()) {
      var source = original.getItems().get(item.getOriginalLineId() - 1);
      var prior = active.stream().flatMap(n -> n.getItems().stream())
              .filter(i -> Objects.equals(i.getOriginalLineId(), item.getOriginalLineId())).toList();
      BigDecimal previousQuantity = active.stream().filter(n -> "07".equals(n.getNote().getReasonCode()))
              .flatMap(n -> n.getItems().stream()).filter(i -> Objects.equals(i.getOriginalLineId(), item.getOriginalLineId())).map(i -> new BigDecimal(i.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal cumulative = previousQuantity.add(new BigDecimal(item.getQuantity()));
      BigDecimal originalQuantity = new BigDecimal(source.getQuantity());
      if (cumulative.compareTo(originalQuantity) > 0)
        throw new ResponseStatusException(CONFLICT, "Las devoluciones acumuladas exceden la cantidad original.");
      var sourceAmounts = LineAmounts.calculateLineAmounts(source);
      BigDecimal previousBase = prior.stream().map(LineAmounts::calculateLineAmounts).map(LineAmounts::base).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal previousTotal = prior.stream().map(LineAmounts::calculateLineAmounts).map(LineAmounts::total).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal base = sourceAmounts.base().subtract(previousBase).multiply(new BigDecimal(item.getQuantity())).divide(originalQuantity.subtract(previousQuantity), 2, RoundingMode.HALF_UP);
      BigDecimal total = sourceAmounts.total().subtract(previousTotal).multiply(new BigDecimal(item.getQuantity())).divide(originalQuantity.subtract(previousQuantity), 2, RoundingMode.HALF_UP);
      BigDecimal previousBags = prior.stream().map(LineAmounts::calculateLineAmounts).map(LineAmounts::icbper).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal bag = sourceAmounts.icbper().subtract(previousBags).multiply(new BigDecimal(item.getQuantity()))
              .divide(originalQuantity.subtract(previousQuantity), 2, RoundingMode.HALF_UP);
      BigDecimal tax = total.subtract(base).subtract(bag);
      if (base.signum() < 0 || tax.signum() < 0 || total.signum() <= 0)
        throw new ResponseStatusException(CONFLICT, "La devolución no tiene importe positivo distribuible; revisa las notas previas.");
      allocated.add(item.toBuilder().creditAllocation(new DocumentItem.CreditAllocation(
              base.toPlainString(), tax.toPlainString(), total.toPlainString(), item.getIcbperQuantity() == null ? null : bag.toPlainString())).build());
    }
    note.setItems(List.copyOf(allocated));
    var amounts = allocated.stream().map(LineAmounts::calculateLineAmounts).toList();
    note.setTotals(pe.invoicing.app.service.document.models.DocumentTotals.builder()
            .icbperAmount(amounts.stream().map(LineAmounts::icbper).reduce(BigDecimal.ZERO, BigDecimal::add).signum() == 0 ? null
                    : amounts.stream().map(LineAmounts::icbper).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString())
            .taxableAmount(sumBase(amounts, "10")).exemptAmount(sumBase(amounts, "20")).unaffectedAmount(sumBase(amounts, "30"))
            .igvAmount(amounts.stream().map(LineAmounts::tax).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString())
            .totalAmount(amounts.stream().map(LineAmounts::total).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString())
            .discountAmount(amounts.stream().map(LineAmounts::discountTotal).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString()).build());
  }

  private static int bagQuantity(BigDecimal quantity) {
    try { return quantity.intValueExact(); }
    catch (ArithmeticException exception) { throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "No se devuelven fracciones de bolsa."); }
  }

  private static String sumBase(List<LineAmounts> lines, String affectation) {
    return lines.stream().filter(line -> affectation.equals(line.affectation())).map(LineAmounts::base)
            .reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString();
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new ResponseStatusException(UNPROCESSABLE_CONTENT, message);
  }
}
