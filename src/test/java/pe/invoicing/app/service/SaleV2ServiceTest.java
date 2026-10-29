package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.document.DocumentService;
import pe.invoicing.app.service.document.impl.*;
import pe.invoicing.app.service.document.models.*;
import pe.invoicing.app.service.document.port.DocumentStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SaleV2ServiceTest {
  final UUID account = UUID.randomUUID(), id = UUID.randomUUID();
  final String ruc = "20123456786";
  final DocumentService documents = mock(DocumentService.class);
  final DocumentStore store = mock(DocumentStore.class);
  final ReconciliationService worker = mock(ReconciliationService.class);
  final AutenticacionService auth = mock(AutenticacionService.class);

  SaleV2Service service(long timeout, String state) {
    when(auth.accountId()).thenReturn(account);
    when(store.findDocument(account, ruc, id)).thenReturn(DocumentData.builder().id(id).status(state).xmlAvailable(true).build());
    return new SaleV2Service(documents, store, worker, auth, timeout);
  }

  @Test @DisplayName("Guardar pendiente no activa ni procesa SUNAT")
  void saveWithoutSend() {
    var service = service(1000, "HELD");
    try {
      when(documents.savePendingDocument(ruc, "sale-key", null)).thenReturn(SubmissionResult.builder().documentId(id).build());
      assertThat(service.save(ruc, "sale-key", null, false, "TICKET_80").document().getStatus()).isEqualTo("HELD");
      verifyNoInteractions(worker);
      verify(store, never()).requestEmission(any(), any(), any());
    } finally { service.close(); }
  }

  @Test @DisplayName("Venta directa reserva emisión duradera sin quedar HELD ante caída")
  void directSalePersistsEmissionIntentBeforeBackgroundWork() {
    var service = service(1000, "DRAFT");
    try {
      when(documents.acceptNewDocument(ruc,"direct-sale-key",null))
              .thenReturn(SubmissionResult.builder().documentId(id).build());
      assertThat(service.save(ruc,"direct-sale-key",null,true,"TICKET_80").document().getId()).isEqualTo(id);
      var order = inOrder(documents,store,worker);
      order.verify(documents).acceptNewDocument(ruc,"direct-sale-key",null);
      order.verify(store).requestEmission(account,ruc,id);
      order.verify(worker).process(new ReconciliationTarget(account,ruc,id));
      verify(documents,never()).savePendingDocument(any(),any(),any());
    } finally { service.close(); }
  }

  @ParameterizedTest @ValueSource(strings = {"SUBMITTED", "ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS"})
  @DisplayName("Imprimir estado verdadero sin exigir falsa aceptación")
  void oneCallReturnsPdf(String state) {
    var service = service(1000, state);
    try {
      var doc = store.findDocument(account, ruc, id);
      when(documents.markGranted(ruc, id)).thenReturn(doc);
      when(documents.downloadPdf(ruc, id, "TICKET_80")).thenReturn(new byte[]{37,80,68,70});
      var result = service.send(ruc, id, "TICKET_80");
      assertThat(result.pdf()).containsExactly((byte)37,(byte)80,(byte)68,(byte)70);
      assertThat(result.document().getStatus()).isEqualTo(state);
      verify(store).requestEmission(account, ruc, id);
      verify(worker).process(new ReconciliationTarget(account,ruc,id));
    } finally { service.close(); }
  }

  @Test @DisplayName("Vencer espera no cancela operación; reintento conserva ID")
  void timeoutDoesNotCancelOrDuplicate() throws Exception {
    var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
    var service = service(20, "DRAFT");
    when(worker.process(any())).thenAnswer(call -> {
      try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return true; }
      finally { finished.countDown(); }
    });
    try {
      var result = service.send(ruc, id, "TICKET_80");
      assertThat(result.pdf()).isNull(); assertThat(result.document().getId()).isEqualTo(id);
      assertThat(finished.getCount()).isEqualTo(1);
      release.countDown(); assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(service.send(ruc, id, "TICKET_80").document().getId()).isEqualTo(id);
      verify(documents, never()).savePendingDocument(any(),any(),any());
    } finally { release.countDown(); service.close(); }
  }

  @ParameterizedTest @ValueSource(strings = {"REJECTED", "UNKNOWN", "DRAFT", "CANCELLED"})
  @DisplayName("Estados no imprimibles no se otorgan como comprobante listo")
  void noPdfForUnreadyDocument(String state) {
    var service = service(1000, state);
    try {
      assertThat(service.send(ruc,id,"TICKET_80").pdf()).isNull();
      verify(documents,never()).markGranted(any(),any());
    } finally { service.close(); }
  }

  @Test @DisplayName("Otra cuenta no puede activar la venta")
  void accessDeniedBeforeWorker() {
    var service = service(1000,"DRAFT");
    when(store.requestEmission(account,ruc,id)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    try {
      assertThatThrownBy(() -> service.send(ruc,id,"TICKET_80")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      verifyNoInteractions(worker);
    } finally { service.close(); }
  }
}
