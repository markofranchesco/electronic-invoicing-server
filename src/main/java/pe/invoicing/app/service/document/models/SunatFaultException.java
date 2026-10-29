package pe.invoicing.app.service.document.models;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

public final class SunatFaultException extends ResponseStatusException {
  private final String code;

  public SunatFaultException(String code, String description) {
    super(HttpStatus.BAD_GATEWAY, "SUNAT " + code + ": " + description);
    this.code = code.substring(code.lastIndexOf('.') + 1);
  }

  public String code() {
    return code;
  }

  public String documentStatus() {
    if (Set.of("1033", "2223", "2324").contains(code)) return "UNKNOWN";
    if (Set.of("0101", "0102", "0103", "0104", "0105", "0106", "0110", "0111", "0112", "0113").contains(code))
      return "SEND_FAILED";
    // Un SOAP Fault sin CDR no acredita el rechazo definitivo del comprobante.
    return "UNKNOWN";
  }
}
