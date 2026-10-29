package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pe.invoicing.app.controller.company.CompanyController;
import pe.invoicing.app.controller.company.mappers.CompanyMapper;
import pe.invoicing.app.controller.document.SaleV2Controller;
import pe.invoicing.app.controller.document.mappers.*;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.document.impl.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CompanyEndpointWorkflowTest extends AbstractServiceTest {
  @Test void missingCompanyThenRegisterVerifyAndSellUsingSameAccount() throws Exception {
    var persisted = new AtomicReference<Company>();
    when(companyStore.getCompany(ACCOUNT,COMPANY)).thenAnswer(call -> {
      if(persisted.get()==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Empresa no encontrada.");
      return persisted.get();
    });
    when(companyStore.saveCompany(eq(ACCOUNT),eq(COMPANY),any(),any(),any())).thenAnswer(call -> {
      var company = Company.builder().id(ACCOUNT).accountId(ACCOUNT).documentNumber(COMPANY)
              .legalName(companyData.legalName()).address(companyData.address()).build();
      persisted.set(company); return company;
    });
    var service = new SaleV2Service(documents,store,new ReconciliationService(store,documents,cancellations),authentication,1000);
    try {
      var mvc = MockMvcBuilders.standaloneSetup(
              new CompanyController(Mappers.getMapper(CompanyMapper.class),companies),
              new SaleV2Controller(service,Mappers.getMapper(DocumentMapper.class),Mappers.getMapper(DocumentResponseMapper.class))).build();
      String companyBody = new org.springframework.core.io.ClassPathResource("companies/valid/request.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      String saleBody = new org.springframework.core.io.ClassPathResource("receipts/cash/request.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      mvc.perform(get("/companies/"+COMPANY)).andExpect(status().isNotFound());
      mvc.perform(post("/v2/companies/"+COMPANY+"/sales").header("Idempotency-Key","company-workflow-key")
              .contentType("application/json").content("{\"document\":"+saleBody+"}"))
              .andExpect(status().isNotFound());
      mvc.perform(put("/companies/"+COMPANY).contentType("application/json").content(companyBody)).andExpect(status().isOk());
      mvc.perform(get("/companies/"+COMPANY)).andExpect(status().isOk())
              .andExpect(jsonPath("$.documentNumber").value(COMPANY))
              .andExpect(jsonPath("$.accountId").value(ACCOUNT.toString()));
      mvc.perform(post("/v2/companies/"+COMPANY+"/sales").header("Idempotency-Key","company-workflow-key")
              .contentType("application/json").content("{\"document\":"+saleBody+"}"))
              .andExpect(status().isAccepted()).andExpect(jsonPath("$.documentId").exists());
    } finally { service.close(); }
  }
}
