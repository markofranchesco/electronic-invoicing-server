package pe.invoicing.app.service.document.impl;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.document.DocumentService;
import pe.invoicing.app.service.document.models.*;
import pe.invoicing.app.service.document.port.DocumentStore;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.*;

@Service
public class SaleV2Service {
  private final DocumentService documents;
  private final DocumentStore store;
  private final ReconciliationService reconciliation;
  private final AutenticacionService auth;
  private final long waitMs;
  private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(100), runnable -> {
            Thread thread = new Thread(runnable, "sale-v2"); thread.setDaemon(true); return thread;
          }, new ThreadPoolExecutor.AbortPolicy());

  public SaleV2Service(DocumentService documents, DocumentStore store, ReconciliationService reconciliation,
                       AutenticacionService auth, @Value("${app.sales.wait-timeout-ms:10000}") long waitMs) {
    if (waitMs < 1 || waitMs > 120000) throw new IllegalArgumentException("app.sales.wait-timeout-ms: 1..120000");
    this.documents = documents; this.store = store; this.reconciliation = reconciliation; this.auth = auth; this.waitMs = waitMs;
  }

  public record Result(DocumentData document, byte[] pdf) {}

  public Result save(String ruc, String key, DocumentDraft draft, boolean send, String format) {
    // La venta directa queda autorizada en la misma reserva duradera: una caída
    // antes de iniciar el hilo no debe dejarla retenida como HELD.
    var id = (send ? documents.acceptNewDocument(ruc, key, draft)
            : documents.savePendingDocument(ruc, key, draft)).getDocumentId();
    return send ? send(ruc, id, format) : status(ruc, id);
  }

  /** Lectura local: no consulta remota ni espera a SUNAT. */
  public Result status(String ruc, UUID id) {
    return new Result(store.findDocument(auth.accountId(), ruc, id), null);
  }

  public Result send(String ruc, UUID id, String format) {
    UUID accountId = auth.accountId();
    store.requestEmission(accountId, ruc, id); // Confirmado antes de iniciar trabajo externo.
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    Future<Result> work;
    try {
      work = executor.submit(() -> {
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
          reconciliation.process(new ReconciliationTarget(accountId, ruc, id));
          var document = store.findDocument(accountId, ruc, id);
          if (Set.of("SUBMITTED", "ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS").contains(document.getStatus())
                  && Boolean.TRUE.equals(document.getXmlAvailable())) {
            document = documents.markGranted(ruc, id);
            return new Result(document, documents.downloadPdf(ruc, id, format));
          }
          return new Result(document, null);
        } finally { SecurityContextHolder.clearContext(); }
      });
    } catch (RejectedExecutionException full) {
      return status(ruc, id); // La cola persistente del conciliador conserva la solicitud.
    }
    try { return work.get(waitMs, TimeUnit.MILLISECONDS); }
    catch (TimeoutException timeout) { return status(ruc, id); } // NO cancelar ni volver a emitir.
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return status(ruc, id); }
    catch (ExecutionException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(failure.getCause());
    }
  }

  @PreDestroy public void close() { executor.shutdown(); }
}
