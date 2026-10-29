package pe.invoicing.app.service.collection;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.authentication.port.SecurityCryptography;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.port.DocumentStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Service
@RequiredArgsConstructor
public class CollectionService {
  private final AutenticacionService auth;
  private final DocumentStore store;
  private final SecurityCryptography crypto;
  private final Clock clock;

  public CollectionBalance balance(String ruc, UUID id) {
    return balance(store.findDocument(auth.accountId(), ruc, id));
  }

  private CollectionBalance balance(DocumentData document) {
    BigDecimal total = new BigDecimal(document.getTotals().getTotalAmount());
    var acceptedNotes = store.findCreditNotes(auth.accountId(), document.getDocumentNumber(), document.getId()).stream()
            .filter(n -> Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS").contains(n.getStatus())).toList();
    var payment = acceptedNotes.stream().filter(n -> "07".equals(n.getDocumentType()) && n.getNote() != null
            && "13".equals(n.getNote().getReasonCode()) && n.getPayment() != null)
            .max(java.util.Comparator.comparing(DocumentData::getCreatedAt))
            .map(DocumentData::getPayment).orElse(document.getPayment());
    BigDecimal withheld = document.getDetraction() == null ? BigDecimal.ZERO
            : document.getDetraction().amountInCurrency(total.toPlainString(), document.getCurrency(), document.getExchangeRate());
    BigDecimal net = total.subtract(withheld);
    BigDecimal initial = payment != null && "CREDIT".equals(payment.getType())
            ? net.subtract(new BigDecimal(payment.getPendingAmount())) : net;
    BigDecimal debited = acceptedNotes.stream().filter(n -> "08".equals(n.getDocumentType()))
            .map(n -> new BigDecimal(n.getTotals().getTotalAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credited = "CANCELLED".equals(document.getStatus()) ? total
            : acceptedNotes.stream().filter(n -> "07".equals(n.getDocumentType()))
                .map(n -> new BigDecimal(n.getTotals().getTotalAmount())).reduce(BigDecimal.ZERO, BigDecimal::add).min(total);
    List<CollectionEntry> entries = document.getCollections() == null ? List.of() : List.copyOf(document.getCollections());
    BigDecimal collected = entries.stream().filter(e -> e.reversedAt() == null)
            .map(e -> new BigDecimal(e.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal creditedToCustomer = total.signum() == 0 ? BigDecimal.ZERO : credited.multiply(net).divide(total, 2, RoundingMode.HALF_UP);
    BigDecimal outstanding = net.add(debited).subtract(creditedToCustomer).subtract(initial).subtract(collected);
    return new CollectionBalance(document.getCurrency(), money(total), money(credited), money(creditedToCustomer), money(withheld), money(debited), money(initial), money(collected),
            money(outstanding.max(BigDecimal.ZERO)), money(outstanding.negate().max(BigDecimal.ZERO)), entries);
  }

  public CollectionEntry collect(String ruc, UUID id, String key, CollectionRequest request) {
    if (key == null || key.isBlank() || key.length() < 8 || key.length() > 200)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Clave de abono de 8 a 200 caracteres requerida.");
    if (request == null || request.amount() == null || !request.amount().matches("[0-9]{1,12}(\\.[0-9]{1,2})?")
            || new BigDecimal(request.amount()).signum() <= 0 || request.paidOn() == null
            || request.paidOn().isAfter(LocalDate.now(clock))
            || request.reference() != null && (request.reference().isBlank() || request.reference().length() > 100))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Revisa importe, fecha y referencia del abono.");
    DocumentData document = store.findDocument(auth.accountId(), ruc, id);
    if (!Objects.equals(document.getCurrency(), request.currency()))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Registra el abono en la moneda del comprobante.");
    String hash = crypto.sha256(key);
    List<CollectionEntry> entries = new ArrayList<>(document.getCollections() == null ? List.of() : document.getCollections());
    for (var entry : entries) if (entry.keyHash().equals(hash)) {
      if (new BigDecimal(entry.amount()).compareTo(new BigDecimal(request.amount())) != 0
              || !entry.paidOn().equals(request.paidOn()) || !Objects.equals(entry.reference(), request.reference()))
        throw new ResponseStatusException(CONFLICT, "La clave ya pertenece a otro abono.");
      return entry; // Incluso revertido: repetir la petición nunca vuelve a cobrar.
    }
    if (!Set.of("01", "03").contains(document.getDocumentType()) || document.getPayment() == null
            || !Set.of("CREDIT", "CASH").contains(document.getPayment().getType())
            || !Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS").contains(document.getStatus())
            || document.getCancellation() != null && !"REJECTED".equals(document.getCancellation().getStatus()))
      throw new ResponseStatusException(CONFLICT, "Sólo se cobran saldos de ventas aceptadas y vigentes.");
    if (request.paidOn().isBefore(document.getIssueDate()))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "El abono no puede ser anterior a la venta.");
    if (store.findCreditNotes(auth.accountId(), ruc, id).stream()
            .anyMatch(n -> n.getCancellation() != null && !Set.of("REJECTED", "ACCEPTED").contains(n.getCancellation().getStatus())
                    || !Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "REJECTED", "CANCELLED").contains(n.getStatus())))
      throw new ResponseStatusException(CONFLICT, "Resuelve las notas pendientes antes de cobrar.");
    BigDecimal amount = new BigDecimal(request.amount());
    if (amount.compareTo(new BigDecimal(balance(document).outstandingAmount())) > 0)
      throw new ResponseStatusException(CONFLICT, "El abono supera el saldo pendiente.");
    CollectionEntry entry = new CollectionEntry(UUID.randomUUID(), hash, money(amount), request.currency(),
            request.paidOn(), request.reference(), OffsetDateTime.now(clock), null, null);
    entries.add(entry);
    // Se conserva la versión del documento: el puerto rechaza una escritura concurrente obsoleta.
    store.saveDocument(auth.accountId(), document.toBuilder().collections(List.copyOf(entries)).build());
    return entry;
  }

  public CollectionEntry reverse(String ruc, UUID id, UUID entryId, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 250)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Indica el motivo de reversión.");
    DocumentData document = store.findDocument(auth.accountId(), ruc, id);
    List<CollectionEntry> entries = new ArrayList<>(document.getCollections() == null ? List.of() : document.getCollections());
    for (int i = 0; i < entries.size(); i++) {
      var entry = entries.get(i);
      if (!entry.id().equals(entryId)) continue;
      if (entry.reversedAt() != null) {
        if (!reason.equals(entry.reversalReason())) throw new ResponseStatusException(CONFLICT, "El abono ya fue revertido con otro motivo.");
        return entry;
      }
      var reversed = new CollectionEntry(entry.id(), entry.keyHash(), entry.amount(), entry.currency(), entry.paidOn(),
              entry.reference(), entry.registeredAt(), OffsetDateTime.now(clock), reason);
      entries.set(i, reversed);
      store.saveDocument(auth.accountId(), document.toBuilder().collections(List.copyOf(entries)).build());
      return reversed;
    }
    throw new ResponseStatusException(NOT_FOUND, "Abono no encontrado.");
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
