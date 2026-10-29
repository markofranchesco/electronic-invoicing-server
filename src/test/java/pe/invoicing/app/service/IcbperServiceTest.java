package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.creditnote.models.*;
import pe.invoicing.app.service.document.models.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class IcbperServiceTest extends AbstractServiceTest {
  @ParameterizedTest @ValueSource(strings={"invoices/cash/request.json","receipts/cash/request.json"})
  void bagsAreSeparateFromIgvAndReportedInSignedXml(String fixture) {
    var draft=draft(fixture);
    var bag=draft.items().getFirst().toBuilder().quantity("3").unitPrice("1.18").discountAmount("0.00").icbperQuantity(3).build();
    var document=saved.get(documents.issueNewDocument(COMPANY,"bags-sale",draft.toBuilder().items(List.of(bag)).build()).getDocumentId());
    assertThat(document.getTotals().getTotalAmount()).isEqualTo("5.04");
    assertThat(document.getTotals().getTaxableAmount()).isEqualTo("3.00");
    assertThat(document.getTotals().getIgvAmount()).isEqualTo("0.54");
    assertThat(document.getTotals().getIcbperAmount()).isEqualTo("1.50");
    assertThat(document.getStatus()).isIn("ACCEPTED","SUBMITTED");
    assertThat(new String(files.get(document.getId()+":XML"),StandardCharsets.UTF_8)).contains("7152","ICBPER","BaseUnitMeasure","PerUnitAmount");
    if ("03".equals(document.getDocumentType())) assertThat(files.values().stream().map(x->new String(x,StandardCharsets.UTF_8))
            .anyMatch(x->x.contains("SummaryDocuments") && x.contains("7152"))).isTrue();
  }
  @ParameterizedTest @ValueSource(strings={"PEN","USD","EUR"})
  void partialBagReturnsCloseAllTaxesWithoutRoundingOverrun(String currency) {
    var draft=draft("invoices/cash/request.json");
    var bag=draft.items().getFirst().toBuilder().quantity("3").unitPrice("1.18").discountAmount("0.00").icbperQuantity(3).build();
    var original=saved.get(documents.issueNewDocument(COMPANY,"bags-return-sale",draft.toBuilder().currency(currency)
            .exchangeRate("PEN".equals(currency)?null:"3.70").items(List.of(bag)).build()).getDocumentId());
    List<DocumentData> previous=new ArrayList<>();
    for(int i=0;i<3;i++) {
      var note=saved.get(notes.accept(COMPANY,original.getId(),"bags-return-"+i,new CreditNoteDraft("07","Retorno de bolsa","FC01",
              LocalDate.now(CLOCK),null,List.of(new CreditNoteDraft.ReturnItem(1,"1")))).getDocumentId());
      CreditNoteRules.validateBalance(original,note,previous);
      assertThat(gateway.signedXml(note,companies.getCompany(COMPANY))).isNotEmpty();
      previous.add(note);
    }
    assertThat(previous.stream().map(n->new BigDecimal(n.getTotals().getIcbperAmount())).reduce(BigDecimal.ZERO,BigDecimal::add))
            .isEqualByComparingTo(original.getTotals().getIcbperAmount());
    assertThat(previous.stream().map(n->new BigDecimal(n.getTotals().getTotalAmount())).reduce(BigDecimal.ZERO,BigDecimal::add))
            .isEqualByComparingTo(original.getTotals().getTotalAmount());
  }
  @Test void bagCountMustMatchWholeUnits() {
    var draft=draft("invoices/cash/request.json");
    assertThatThrownBy(()->documents.acceptNewDocument(COMPANY,"invalid-bags",draft.toBuilder().items(List.of(
            draft.items().getFirst().toBuilder().quantity("1.5").icbperQuantity(2).build())).build()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
}
