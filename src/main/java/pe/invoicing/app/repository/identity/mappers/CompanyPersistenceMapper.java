package pe.invoicing.app.repository.identity.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;
import pe.invoicing.app.service.company.models.Address;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.company.models.CompanyData;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CompanyPersistenceMapper {
  @Mapping(target = "ready", constant = "false")
  @Mapping(target = "taxCredentialsConfigured", constant = "false")
  @Mapping(target = "digitalCertificateConfigured", constant = "false")
  Company toCompany(TaxpayerEntity entity);

  Address toAddress(TaxpayerEntity.Address address);

  TaxpayerEntity.Address toAddressEntity(Address address);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "documentNumber", ignore = true)
  @Mapping(target = "accountId", ignore = true)
  @Mapping(target = "account", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "encryptedCredentials", source = "encryptedCredentials")
  @Mapping(target = "encryptedCertificate", source = "encryptedCertificate")
  void update(
          CompanyData source,
          String encryptedCertificate,
          String encryptedCredentials,
          @MappingTarget TaxpayerEntity target);
}
