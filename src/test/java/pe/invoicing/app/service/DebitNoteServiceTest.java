package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.debitnote.*;
import pe.invoicing.app.service.document.models.*;
import pe.invoicing.app.service.collection.CollectionService;
import java.time.LocalDate;
import java.util.List;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DebitNoteServiceTest extends AbstractServiceTest {
  private DocumentData original(String fixture) {
    var doc = issue(fixture, "debit-original");
    doc.setStatus("ACCEPTED");
    documents.markGranted(COMPANY, doc.getId());
    return saved.get(doc.getId());
  }
  private DebitNoteDraft request(String reason, String series, String amount) {
    return new DebitNoteDraft(reason, "Ajuste posterior", series, LocalDate.now(CLOCK), null,
            List.of(new DebitNoteDraft.Charge(1, amount, null)));
  }
  @ParameterizedTest @ValueSource(strings = {"01", "02"})
  void invoiceDebitSignsValidXmlAndIncreasesDebt(String reason) {
    var original = original("invoices/installments/request.json");
    var result = new DebitNoteService(documents).issue(COMPANY, original.getId(), "debit-new", request(reason,"FD01","11.80"));
    var note = saved.get(result.getDocumentId());
    DebitNoteRules.validate(original,note,List.of());
    assertThat(note.getStatus()).isEqualTo("ACCEPTED");
    String xml = new String(files.get(note.getId()+":XML"),StandardCharsets.UTF_8);
    assertThat(xml).contains("DebitNote", "RequestedMonetaryTotal", "DebitedQuantity", "11.80").doesNotContain("InvoiceTypeCode", "LegalMonetaryTotal");
    when(store.findCreditNotes(ACCOUNT,COMPANY,original.getId())).thenReturn(List.of(note));
    var balance = new CollectionService(authentication,store,crypto,CLOCK).balance(COMPANY,original.getId());
    assertThat(balance.debitedAmount()).isEqualTo("11.80");
    assertThat(balance.creditedAmount()).isEqualTo("0.00");
    assertThat(new java.math.BigDecimal(balance.outstandingAmount())).isEqualByComparingTo(new java.math.BigDecimal(original.getPayment().getPendingAmount()).add(new java.math.BigDecimal("11.80")));
  }
  @Test void receiptDebitIsDurableAndRecoverable() {
    var original=original("receipts/cash/request.json");
    clearInvocations(gateway);
    var result=new DebitNoteService(documents).accept(COMPANY,original.getId(),"debit-durable",request("02","BD01","10.00"));
    var reserved=saved.get(result.getDocumentId());
    assertThat(reserved.getStatus()).isEqualTo("DRAFT");
    verify(gateway,never()).sendBill(any(),any());
    var recovered=documents.recoverDocument(COMPANY,reserved.getId(),null);
    assertThat(recovered.getStatus()).isEqualTo("SUBMITTED");
    assertThat(recovered.getNumber()).isEqualTo(reserved.getNumber());
    assertThat(new String(files.get(reserved.getId()+":XML"),StandardCharsets.UTF_8)).contains("DebitNote");
  }
  @ParameterizedTest @ValueSource(strings={"0","-1","1.001","x"})
  void invalidChargeNeverReserves(String amount) {
    var original=original("invoices/cash/request.json");
    clearInvocations(store);
    assertThatThrownBy(()->new DebitNoteService(documents).accept(COMPANY,original.getId(),"invalid-debit",request("02","FD01",amount)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(store,never()).createIssuance(any(),any(),anyString(),anyString());
  }
  @Test void ungrantedSaleCannotBeDebited() {
    var original=issue("invoices/cash/request.json","ungranted-original");
    assertThatThrownBy(()->new DebitNoteService(documents).accept(COMPANY,original.getId(),"ungranted-debit",request("02","FD01","10")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void fullReturnBlocksFurtherChargesUnderLock() {
    var original=original("invoices/cash/request.json");
    var id=new DebitNoteService(documents).accept(COMPANY,original.getId(),"debit-after-return",request("02","FD01","10")).getDocumentId();
    var credit=original.toBuilder().documentType("07").status("DRAFT").note(NoteDetails.builder().reasonCode("06").build()).build();
    assertThatThrownBy(()->DebitNoteRules.validate(original,saved.get(id),List.of(credit)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void lostDebitResponseReusesSameIdAndDoesNotReserveAgain() {
    var original=original("invoices/cash/request.json");
    var key="same-debit-request";
    var body=request("02","FD01","11.80");
    var first=new DebitNoteService(documents).accept(COMPANY,original.getId(),key,body);
    var captor=org.mockito.ArgumentCaptor.forClass(String.class);
    verify(store).createIssuance(eq(ACCOUNT),argThat(d->"08".equals(d.getDocumentType())),eq(crypto.sha256(key)),captor.capture());
    when(artifacts.findIssueOperation(ACCOUNT,COMPANY,crypto.sha256(key)))
            .thenReturn(java.util.Optional.of(new IssueOperation(captor.getValue(),first.getDocumentId())));
    clearInvocations(store);
    assertThat(new DebitNoteService(documents).accept(COMPANY,original.getId(),key,body).getDocumentId()).isEqualTo(first.getDocumentId());
    assertThatThrownBy(()->new DebitNoteService(documents).accept(COMPANY,original.getId(),key,request("02","FD01","12.00")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(store,never()).createIssuance(any(),any(),anyString(),anyString());
  }

  @Test void aCashSaleWithAcceptedDebitHasACollectibleBalance() {
    var original=original("invoices/cash/request.json");
    var noteId=new DebitNoteService(documents).issue(COMPANY,original.getId(),"cash-debit",request("02","FD01","11.80")).getDocumentId();
    when(store.findCreditNotes(ACCOUNT,COMPANY,original.getId())).thenReturn(List.of(saved.get(noteId)));
    var service=new CollectionService(authentication,store,crypto,CLOCK);
    service.collect(COMPANY,original.getId(),"cash-debit-payment",new pe.invoicing.app.service.collection.CollectionRequest("11.80","PEN",LocalDate.now(CLOCK),null));
    assertThat(service.balance(COMPANY,original.getId()).outstandingAmount()).isEqualTo("0.00");
  }

}
