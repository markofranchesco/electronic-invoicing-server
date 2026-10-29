package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.document.models.*;
import pe.invoicing.app.service.collection.CollectionService;
import java.util.List;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class DetractionServiceTest extends AbstractServiceTest {
  private DocumentDraft request(String currency) {
    var draft=draft("invoices/cash/request.json");
    return draft.toBuilder().currency(currency).exchangeRate("PEN".equals(currency)?null:"3.70")
            .operationType("1001").detraction(new Detraction("037","00123456789"))
            .items(List.of(draft.items().getFirst().toBuilder().unitPrice("1180.00").quantity("1").build())).build();
  }
  @ParameterizedTest @ValueSource(strings={"PEN","USD","EUR"})
  void detractionSignsInPenAndDoesNotChangeSaleTax(String currency) {
    var original=saved.get(documents.issueNewDocument(COMPANY,"detraction-cash",request(currency)).getDocumentId());
    assertThat(original.getStatus()).isEqualTo("ACCEPTED");
    assertThat(original.getTotals().getTotalAmount()).isEqualTo("1180.00");
    assertThat(original.getTotals().getIgvAmount()).isEqualTo("180.00");
    String xml=new String(files.get(original.getId()+":XML"),StandardCharsets.UTF_8);
    assertThat(xml).contains("2006","Detraccion","00123456789","PaymentPercent");
    assertThat(xml).contains("currencyID=\"PEN\">");
  }
  @Test void creditBalanceExcludesDetractionAndShowsItSeparately() {
    var draft=request("PEN").toBuilder().payment(Payment.builder().type("CREDIT").pendingAmount("1038.00")
            .installments(List.of(Installment.builder().amount("1038.00").dueDate(LocalDate.now(CLOCK).plusDays(30)).build())).build()).build();
    var original=saved.get(documents.issueNewDocument(COMPANY,"detraction-credit",draft).getDocumentId());
    var balance=new CollectionService(authentication,store,crypto,CLOCK).balance(COMPANY,original.getId());
    assertThat(balance.initialPayment()).isEqualTo("0.00");
    assertThat(balance.detractionAmount()).isEqualTo("142.00");
    assertThat(balance.outstandingAmount()).isEqualTo("1038.00");
  }
  @Test void creditCannotIncludeDepositInInstallments() {
    var draft=request("PEN").toBuilder().payment(Payment.builder().type("CREDIT").pendingAmount("1180.00")
            .installments(List.of(Installment.builder().amount("1180.00").dueDate(LocalDate.now(CLOCK).plusDays(30)).build())).build()).build();
    assertThatThrownBy(()->documents.acceptNewDocument(COMPANY,"wrong-spot-credit",draft))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void thresholdAndUnsupportedOperationAreRejectedBeforeIssuing() {
    var draft=request("PEN");
    assertThatThrownBy(()->documents.acceptNewDocument(COMPANY,"small-spot",draft.toBuilder().items(List.of(
            draft.items().getFirst().toBuilder().unitPrice("700.00").build())).build()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(()->documents.acceptNewDocument(COMPANY,"wrong-spot-code",draft.toBuilder().detraction(new Detraction("004","00123456789")).build()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
}
