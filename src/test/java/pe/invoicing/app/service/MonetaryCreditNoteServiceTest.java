package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.creditnote.models.*;
import pe.invoicing.app.service.document.models.*;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MonetaryCreditNoteServiceTest extends AbstractServiceTest {
  private CreditNoteDraft request(String reason, String amount) {
    return new CreditNoteDraft(reason,"Ajuste comercial","FC01",LocalDate.now(CLOCK),null,null,
            "04".equals(reason) ? amount : null,
            "04".equals(reason) ? null : List.of(new CreditNoteDraft.Adjustment(1,amount)));
  }
  @ParameterizedTest @ValueSource(strings={"04","05","09"})
  void monetaryAdjustmentSignsOnlyTheReduction(String reason) {
    var original=issue("invoices/cash/request.json","discount-original");
    var note=saved.get(notes.issue(COMPANY,original.getId(),"discount-note",request(reason,"11.80")).getDocumentId());
    CreditNoteRules.validateBalance(original,note,List.of());
    assertThat(note.getStatus()).isEqualTo("ACCEPTED");
    assertThat(note.getTotals().getTotalAmount()).isEqualTo("11.80");
    assertThat(note.getTotals().getIgvAmount()).isEqualTo("1.80");
    assertThat(files.get(note.getId()+":XML")).isNotEmpty();
    var excessive=saved.get(notes.accept(COMPANY,original.getId(),"discount-excess",request(reason,"118.00")).getDocumentId());
    assertThatThrownBy(()->CreditNoteRules.validateBalance(original,excessive,List.of(note)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void returnAfterDiscountClosesRemainingAmountAndTax() {
    var original=issue("invoices/cash/request.json","mixed-original");
    var discount=saved.get(notes.issue(COMPANY,original.getId(),"mixed-discount",request("05","11.80")).getDocumentId());
    var returned=saved.get(notes.accept(COMPANY,original.getId(),"mixed-return",new CreditNoteDraft("07","Devolver saldo","FC01",
            LocalDate.now(CLOCK),null,List.of(new CreditNoteDraft.ReturnItem(1,original.getItems().getFirst().getQuantity())))).getDocumentId());
    CreditNoteRules.validateBalance(original,returned,List.of(discount));
    assertThat(returned.getTotals().getTotalAmount()).isEqualTo("106.20");
    assertThat(returned.getTotals().getIgvAmount()).isEqualTo("16.20");
    assertThat(gateway.signedXml(returned,companies.getCompany(COMPANY))).isNotEmpty();
  }
  @Test void wrongRucCancellationPreservesOriginalRecipient() {
    var original=issue("invoices/cash/request.json","wrong-ruc-original");
    var note=saved.get(notes.issue(COMPANY,original.getId(),"wrong-ruc-note",new CreditNoteDraft("02","RUC consignado por error","FC01",LocalDate.now(CLOCK),null)).getDocumentId());
    CreditNoteRules.validateBalance(original,note,List.of());
    assertThat(note.getCustomer()).isEqualTo(original.getCustomer());
    assertThat(note.getTotals()).isEqualTo(original.getTotals());
  }
  @Test void globalDiscountDistributesAllCentsAcrossMixedTaxes() {
    var draft=draft("invoices/cash/request.json");
    var item=draft.items().getFirst().toBuilder().unitPrice("1.00").quantity("1").build();
    var original=saved.get(documents.issueNewDocument(COMPANY,"mixed-tax-sale",draft.toBuilder().items(List.of(item,
            item.toBuilder().taxAffectationCode("20").build(),item.toBuilder().taxAffectationCode("30").build())).build()).getDocumentId());
    var note=saved.get(notes.issue(COMPANY,original.getId(),"mixed-tax-discount",request("04","0.10")).getDocumentId());
    CreditNoteRules.validateBalance(original,note,List.of());
    assertThat(note.getTotals().getTotalAmount()).isEqualTo("0.10");
    assertThat(note.getItems()).hasSize(3);
  }
}
