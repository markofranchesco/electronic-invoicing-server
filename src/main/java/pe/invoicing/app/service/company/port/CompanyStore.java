package pe.invoicing.app.service.company.port;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.company.models.CompanyData;

import java.util.Optional;
import java.util.UUID;

public interface CompanyStore {

  Company getCompany(UUID accountId, String documentNumber);

  Company saveCompany(
          UUID accountId,
          String documentNumber,
          CompanyData request,
          String encryptedCertificate,
          String encryptedCredentials);

  Optional<String> findEncryptedCertificate(UUID accountId, String documentNumber);

  Optional<String> findEncryptedCredentials(UUID accountId, String documentNumber);

  Page<Company> listCompanies(UUID accountId, Pageable pageable);

  void deleteCompany(UUID accountId, String documentNumber);
}
