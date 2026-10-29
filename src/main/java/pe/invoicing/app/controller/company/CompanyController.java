package pe.invoicing.app.controller.company;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.company.dtos.requests.CompanyUpsertRequest;
import pe.invoicing.app.controller.company.dtos.responses.CompanyResponse;
import pe.invoicing.app.controller.company.mappers.CompanyMapper;
import pe.invoicing.app.controller.shared.dtos.responses.MessageResponse;
import pe.invoicing.app.controller.shared.dtos.responses.PageResponse;
import pe.invoicing.app.service.company.CompanyService;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/companies")
public class CompanyController {
  private final CompanyMapper mapper;
  private final CompanyService companies;

  @GetMapping
  public PageResponse<CompanyResponse> listCompanies(@RequestParam(defaultValue = "1") @Min(value = 1, message = InputMessages.MINIMUM) int page,
                                                     @RequestParam(defaultValue = "10") @Min(value = 1, message = InputMessages.MINIMUM) @Max(value = 100, message = InputMessages.MAXIMUM) int perPage) {
    return PageResponse.from(companies.listCompanies(PageRequest.of(page - 1, perPage, Sort.by(Sort.Direction.DESC, "createdAt"))).map(mapper::toApi));
  }

  @PutMapping("/{documentNumber}")
  public MessageResponse upsertCompany(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                       @Valid @RequestBody CompanyUpsertRequest request) {
    companies.upsertCompany(documentNumber, mapper.toService(request));
    return MessageResponse.builder().message("Empresa guardada correctamente.").build();
  }

  @GetMapping("/{documentNumber}")
  public CompanyResponse getCompany(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber) {
    return mapper.toApi(companies.getCompany(documentNumber));
  }

  @DeleteMapping("/{documentNumber}")
  public MessageResponse deleteCompany(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber) {
    companies.deleteCompany(documentNumber);
    return MessageResponse.builder().message("Empresa eliminada correctamente.").build();
  }
}
