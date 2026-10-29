package pe.invoicing.app.service.company.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.sunat.properties.SunatProperties;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.authentication.port.SecretVault;
import pe.invoicing.app.service.company.CompanyService;
import pe.invoicing.app.service.company.models.CertificateBundle;
import pe.invoicing.app.service.company.models.CertificateMetadata;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.company.models.CompanyData;
import pe.invoicing.app.service.company.port.CertificateInspector;
import pe.invoicing.app.service.company.port.CompanyStore;
import pe.invoicing.app.service.company.port.SeriesStore;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Service
@RequiredArgsConstructor
public class CompanyServiceImpl implements CompanyService {
  private final AutenticacionService authentication;
  private final CompanyStore companies;
  private final CertificateInspector certificateInspector;
  private final SecretVault vault;
  private final SeriesStore series;
  private final Clock clock;
  private final SunatProperties sunat;

  @Override
  public Page<Company> listCompanies(Pageable pageable) {
    return companies.listCompanies(authentication.accountId(), pageable).map(this::withReadiness);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Company upsertCompany(String documentNumber, CompanyData data) {
    if ((data.specialIgvFrom() == null) != (data.specialIgvUntil() == null)
            || data.specialIgvFrom() != null && data.specialIgvUntil().isBefore(data.specialIgvFrom()))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Indica un periodo válido de elegibilidad del IGV especial.");
    pe.invoicing.app.service.company.models.BusinessLogo.validate(data.logoBase64());
    byte[] certificate;
    try {
      certificate = Base64.getDecoder().decode(data.certificate());
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Certificado PKCS#12 Base64 inválido.");
    }
    if (certificate.length == 0
            || certificate.length > 1024 * 1024
            || !StringUtils.hasText(data.certificatePassword())
            || !StringUtils.hasText(data.solUsername())
            || !StringUtils.hasText(data.solPassword())) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Certificado y credenciales SOL obligatorios.");
    }
    CertificateMetadata details =
            certificateInspector.inspect(certificate, data.certificatePassword());
    if (!details.valid() || !details.subject().contains(documentNumber)) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Certificado vencido o no corresponde al TAX_NUMBER.");
    }

    Company company =
            companies.saveCompany(
                    authentication.accountId(),
                    documentNumber,
                    data,
                    vault.encrypt(
                            new CertificateBundle(data.certificate(), data.certificatePassword()).serialize()),
                    vault.encrypt(data.solUsername() + ":" + data.solPassword()));

    List<String[]> initialSeries =
            List.of(
                    new String[]{"01", "F001"}, new String[]{"03", "B001"},
                    new String[]{"07", "FC01"}, new String[]{"07", "BC01"},
                    new String[]{"RA", "RA01"}, new String[]{"RC", "RC01"});
    for (String[] entry : initialSeries) {
      series.ensureSequence(authentication.accountId(), documentNumber, entry[0], entry[1]);
    }
    return withReadiness(company);
  }

  @Override
  public Company getCompany(String documentNumber) {
    return withReadiness(companies.getCompany(authentication.accountId(), documentNumber));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteCompany(String documentNumber) {
    companies.deleteCompany(authentication.accountId(), documentNumber);
  }

  private Company withReadiness(Company company) {
    boolean credentials =
            companies
                    .findEncryptedCredentials(authentication.accountId(), company.getDocumentNumber())
                    .isPresent();
    boolean certificate =
            companies
                    .findEncryptedCertificate(authentication.accountId(), company.getDocumentNumber())
                    .map(vault::decrypt)
                    .filter(value -> value.startsWith("v2:"))
                    .map(CertificateBundle::parse)
                    .map(
                            stored ->
                                    certificateInspector.inspect(
                                            Base64.getDecoder().decode(stored.content()), stored.password()))
                    .filter(
                            details ->
                                    details.valid() && details.expiresAt().isAfter(OffsetDateTime.now(clock)))
                    .isPresent();
    boolean numbered =
            !series.listSequences(authentication.accountId(), company.getDocumentNumber()).isEmpty();
    boolean ready = credentials && certificate && numbered;
    company.setReady(ready);
    company.setTaxCredentialsConfigured(credentials);
    company.setDigitalCertificateConfigured(certificate);
    return company;
  }
}
