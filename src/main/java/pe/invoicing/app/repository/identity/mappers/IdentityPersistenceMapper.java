package pe.invoicing.app.repository.identity.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.repository.identity.entity.AccountEntity;
import pe.invoicing.app.repository.identity.entity.SessionEntity;
import pe.invoicing.app.service.authentication.models.AccountDetails;
import pe.invoicing.app.service.session.models.SessionDetails;
import pe.invoicing.app.service.session.models.TokenDetails;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface IdentityPersistenceMapper {
  SessionDetails toSession(SessionEntity entity);

  @Mapping(target = "passwordHash", source = "password")
  AccountDetails toDetails(AccountEntity account);

  @Mapping(target = "client", ignore = true)
  TokenDetails toToken(SessionEntity session);

  @Mapping(target = "ipAddress", source = "client.ipAddress")
  @Mapping(target = "userAgent", source = "client.userAgent")
  @Mapping(target = "acceptLanguage", source = "client.acceptLanguage")
  @Mapping(target = "browser", source = "client.browser")
  @Mapping(target = "operatingSystem", source = "client.operatingSystem")
  @Mapping(target = "deviceType", source = "client.deviceType")
  @Mapping(target = "account", ignore = true)
  @Mapping(target = "revokedAt", ignore = true)
  SessionEntity toEntity(TokenDetails token);
}
