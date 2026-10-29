package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import pe.invoicing.app.service.creditnote.models.*;
import pe.invoicing.app.service.document.models.*;
import pe.invoicing.app.service.collection.CollectionService;
import java.time.LocalDate;
import java.util.List;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class InformationalCreditNoteServiceTest extends AbstractServiceTest {
  @Test void descriptionCorrectionHasZeroFiscalImpact() {
    var original=issue("invoices/cash/request.json","description-original");
    var request=new CreditNoteDraft("03","Corregir descripción","FC01",LocalDate.now(CLOCK),null,null,null,null,
            List.of(new CreditNoteDraft.DescriptionCorrection(1,"Consulta veterinaria")),null);
    var note=saved.get(notes.issue(COMPANY,original.getId(),"description-note",request).getDocumentId());
    CreditNoteRules.validateBalance(original,note,List.of());
    assertThat(note.getStatus()).isEqualTo("ACCEPTED");
    assertThat(note.getTotals().getTotalAmount()).isEqualTo("0.00");
    assertThat(note.getTotals().getIgvAmount()).isEqualTo("0.00");
    assertThat(new String(files.get(note.getId()+":XML"),StandardCharsets.UTF_8)).contains("Consulta veterinaria");
    var refund=saved.get(notes.accept(COMPANY,original.getId(),"refund-after-description",
            new CreditNoteDraft("06","Devolución","FC01",LocalDate.now(CLOCK),null)).getDocumentId());
    CreditNoteRules.validateBalance(original,refund,List.of(note));
    assertThat(refund.getTotals()).isEqualTo(original.getTotals());
  }
  @Test void correctedInstallmentsChangeReceivableWithoutChangingSaleXml() {
    var original=issue("invoices/installments/request.json","schedule-original");
    byte[] before=files.get(original.getId()+":XML").clone();
    var payment=Payment.builder().type("CREDIT").pendingAmount("10.00").installments(List.of(
            Installment.builder().amount("10.00").dueDate(LocalDate.now(CLOCK).plusDays(40)).build())).build();
    var note=saved.get(notes.issue(COMPANY,original.getId(),"schedule-note",new CreditNoteDraft("13","Corregir cuotas",
            "FC01",LocalDate.now(CLOCK),null,null,null,null,null,payment)).getDocumentId());
    CreditNoteRules.validateBalance(original,note,List.of());
    assertThat(note.getStatus()).isEqualTo("ACCEPTED");
    assertThat(note.getTotals().getTotalAmount()).isEqualTo("0.00");
    assertThat(new String(files.get(note.getId()+":XML"),StandardCharsets.UTF_8)).contains("PaymentTerms","Credito","Cuota001",">10.00<");
    assertThat(files.get(original.getId()+":XML")).containsExactly(before);
    when(store.findCreditNotes(ACCOUNT,COMPANY,original.getId())).thenReturn(List.of(note));
    assertThat(new CollectionService(authentication,store,crypto,CLOCK).balance(COMPANY,original.getId()).outstandingAmount()).isEqualTo("10.00");
  }
  @Test void scheduleCorrectionRejectsCashSale() {
    var original=issue("invoices/cash/request.json","cash-schedule-original");
    assertThatThrownBy(()->notes.accept(COMPANY,original.getId(),"cash-schedule-note",new CreditNoteDraft("13","Corregir cuotas",
            "FC01",LocalDate.now(CLOCK),null,null,null,null,null,Payment.builder().type("CREDIT").build())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void receiptDescriptionCorrectionUsesSummaryAndZeroAmounts() {
    var original=issue("receipts/cash/request.json","receipt-description-original");
    original.setStatus("ACCEPTED");
    documents.markGranted(COMPANY,original.getId());
    var note=saved.get(notes.issue(COMPANY,original.getId(),"receipt-description-note",new CreditNoteDraft("03","Corregir descripción",
            "BC01",LocalDate.now(CLOCK),null,null,null,null,List.of(new CreditNoteDraft.DescriptionCorrection(1,"Producto corregido")),null)).getDocumentId());
    assertThat(note.getStatus()).isEqualTo("SUBMITTED");
    assertThat(note.getTotals().getTotalAmount()).isEqualTo("0.00");
    CreditNoteRules.validateBalance(original,note,List.of());
  }
  @Test void correctedInstallmentMayBeOverdueButMustFollowOriginalInvoiceDate() {
    var draft=draft("invoices/installments/request.json").toBuilder().issueDate(LocalDate.now(CLOCK).minusDays(2)).build();
    var original=saved.get(documents.issueNewDocument(COMPANY,"overdue-original",draft).getDocumentId());
    var payment=Payment.builder().type("CREDIT").pendingAmount("10.00").installments(List.of(
            Installment.builder().amount("10.00").dueDate(LocalDate.now(CLOCK).minusDays(1)).build())).build();
    var note=saved.get(notes.issue(COMPANY,original.getId(),"overdue-correction",new CreditNoteDraft("13","Corregir fecha anterior",
            "FC01",LocalDate.now(CLOCK),null,null,null,null,null,payment)).getDocumentId());
    CreditNoteRules.validateBalance(original,note,List.of());
    assertThat(note.getStatus()).isEqualTo("ACCEPTED");
  }

}
