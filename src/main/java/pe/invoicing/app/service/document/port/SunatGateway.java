package pe.invoicing.app.service.document.port;

import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;

import java.time.LocalDate;

public interface SunatGateway {
  void validateSupported(DocumentData document);

  void checkReady(String documentNumber);

  byte[] signedXml(DocumentData document, Company company);

  SunatSendResult sendBill(DocumentData document, byte[] signedXml);

  byte[] signedVoid(
          DocumentData document, Company company, String reference, LocalDate date, String reason);

  byte[] signedSummary(
          DocumentData document, Company company, String reference, LocalDate date, String condition);

  String sendSummary(String documentNumber, String reference, byte[] signedXml);

  SunatStatus getSummaryStatus(String documentNumber, String ticket, String reference);

  SunatStatus consultBill(DocumentData document);
}
