package pe.invoicing.app.service.document.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.cancellation.CancellationService;
import pe.invoicing.app.service.document.DocumentService;
import pe.invoicing.app.service.document.models.ReconciliationTarget;
import pe.invoicing.app.service.document.port.DocumentStore;
import pe.invoicing.app.service.session.models.AuthPrincipal;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReconciliationService {
  private final DocumentStore store;
  private final DocumentService documents;
  private final CancellationService cancellations;

  public void runBatch() {
    int number = 0;
    org.springframework.data.domain.Page<ReconciliationTarget> page;
    do {
      page = store.reconciliationCandidates(PageRequest.of(number++, 100, Sort.by("id")));
      for (var target : page) {
        try {
          process(target);
        } catch (RuntimeException ignored) { /* La reserva vence y permite recuperación posterior. */ }
      }
    } while (page.hasNext());
  }

  public boolean process(ReconciliationTarget target) {
    var claimed = store.claimReconciliation(target.accountId(), target.documentNumber(), target.documentId());
    if (claimed.isEmpty()) return false;
    var previous = SecurityContextHolder.getContext();
    String failure = null;
    try {
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
              new AuthPrincipal(new UUID(0, 0), target.accountId()), "", List.of()));
      SecurityContextHolder.setContext(context);
      var document = documents.recoverDocument(target.documentNumber(), target.documentId(), null);
      if (document.getCancellation() != null)
        cancellations.recoverCancellation(target.documentNumber(), target.documentId(), null);
    } catch (RuntimeException exception) {
      failure = (exception instanceof ResponseStatusException response && response.getStatusCode().is4xxClientError()
              ? "MANUAL:" : "RETRY:") + exception.getMessage();
    } finally {
      SecurityContextHolder.setContext(previous);
      store.finishReconciliation(target.accountId(), target.documentNumber(), target.documentId(), claimed.orElseThrow(), failure);
    }
    return failure == null;
  }
}
