package pe.invoicing.app.service.company;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.company.models.CompanyData;

public interface CompanyService {
  Page<Company> listCompanies(Pageable pageable);

  Company upsertCompany(String documentNumber, CompanyData body);

  Company getCompany(String documentNumber);

  void deleteCompany(String documentNumber);
}
