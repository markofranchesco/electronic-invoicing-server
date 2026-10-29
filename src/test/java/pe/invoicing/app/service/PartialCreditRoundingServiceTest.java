package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;
import pe.invoicing.app.service.creditnote.models.CreditNoteRules;
import pe.invoicing.app.service.document.models.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartialCreditRoundingServiceTest extends AbstractServiceTest {
  private DocumentData note(DocumentData original, int number) {
    var request = new CreditNoteDraft("07", "Devolución por unidad", "FC01", original.getIssueDate(), null,
            List.of(new CreditNoteDraft.ReturnItem(1, "1")));
    return original.toBuilder().id(UUID.randomUUID()).sourceDocumentId(original.getId())
            .documentType("07").series("FC01").number((long) number).status("DRAFT")
            .items(CreditNoteRules.items(original, request))
            .note(NoteDetails.builder().reasonCode("07").reason(request.reason()).originalDocument(
                    DocumentReference.builder().documentType("01").series(original.getSeries()).number(original.getNumber()).build()).build())
            .build();
  }

  @ParameterizedTest
  @CsvSource({"3,1.00,0.00,10", "7,0.99,0.05,10", "3,1.00,0.01,20", "7,1.23,0.17,30"})
  @DisplayName("Devolver todas las unidades por partes cierra importes e impuestos exactos")
  void whenReturningOneAtATimeThenAllocateRemainderWithoutOvercredit(int quantity, String price, String discount, String affectation) {
    var draft = draft("invoices/cash/request.json");
    var item = draft.items().getFirst().toBuilder().quantity(Integer.toString(quantity))
            .unitPrice(price).discountAmount(discount).taxAffectationCode(affectation).build();
    var id = documents.issueNewDocument(COMPANY, "rounding-original-sale", draft.toBuilder().items(List.of(item)).build()).getDocumentId();
    var original = saved.get(id);
    List<DocumentData> previous = new ArrayList<>();
    for (int i = 1; i <= quantity; i++) {
      var note = note(original, i);
      CreditNoteRules.validateBalance(original, note, previous);
      // El generador real vuelve a validar importes y XSD y firma la nota ajustada.
      assertThat(gateway.signedXml(note, companies.getCompany(COMPANY))).isNotEmpty();
      var restored = JSON.readValue(JSON.writeValueAsString(note), DocumentData.class);
      assertThat(LineAmounts.calculateLineAmounts(restored.getItems().getFirst()).total())
              .isEqualByComparingTo(note.getTotals().getTotalAmount());
      note.setStatus("ACCEPTED");
      previous.add(note);
    }
    BigDecimal total = previous.stream().map(n -> new BigDecimal(n.getTotals().getTotalAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal tax = previous.stream().map(n -> new BigDecimal(n.getTotals().getIgvAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal base = previous.stream().flatMap(n -> n.getItems().stream()).map(LineAmounts::calculateLineAmounts)
            .map(LineAmounts::base).reduce(BigDecimal.ZERO, BigDecimal::add);
    var expected = LineAmounts.calculateLineAmounts(original.getItems().getFirst());
    assertThat(total).isEqualByComparingTo(expected.total());
    assertThat(tax).isEqualByComparingTo(expected.tax());
    assertThat(base).isEqualByComparingTo(expected.base());
    assertThatThrownBy(() -> CreditNoteRules.validateBalance(original, note(original, quantity + 1), previous))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
}
