package pe.invoicing.app.controller.company.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.controller.company.dtos.requests.CompanyUpsertRequest;
import pe.invoicing.app.controller.company.dtos.responses.CompanyResponse;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.company.models.CompanyData;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CompanyMapper {
  @Mapping(target = "certificate", source = "digitalCertificate.certificate")
  @Mapping(target = "certificatePassword", source = "digitalCertificate.password")
  @Mapping(target = "solUsername", source = "taxCredentials.user")
  @Mapping(target = "solPassword", source = "taxCredentials.password")
  CompanyData toService(CompanyUpsertRequest body);

  CompanyResponse toApi(Company company);
}
