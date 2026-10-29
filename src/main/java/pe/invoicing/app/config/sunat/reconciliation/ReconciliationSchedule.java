package pe.invoicing.app.config.sunat.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import pe.invoicing.app.service.document.impl.ReconciliationService;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class ReconciliationSchedule {
  private final ReconciliationService reconciliation;

  @Scheduled(fixedDelayString = "${app.sunat.reconciliation-delay-ms:60000}", initialDelayString = "${app.sunat.reconciliation-delay-ms:60000}")
  public void reconcile() {
    reconciliation.runBatch();
  }
}
