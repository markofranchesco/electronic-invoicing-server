package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pe.invoicing.app.controller.document.SaleV2Controller;
import pe.invoicing.app.controller.document.mappers.DocumentMapper;
import pe.invoicing.app.controller.document.mappers.DocumentResponseMapper;
import pe.invoicing.app.service.document.impl.SaleV2Service;
import pe.invoicing.app.service.document.models.DocumentData;
import java.time.LocalDate;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SaleV2ControllerTest {
  final UUID id = UUID.randomUUID();
  final SaleV2Service sales = mock(SaleV2Service.class);
  final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new SaleV2Controller(sales,
          Mappers.getMapper(DocumentMapper.class),Mappers.getMapper(DocumentResponseMapper.class))).build();
  final String url = "/v2/companies/20123456786/sales/"+id+"/recover";

  DocumentData document(String state) { return DocumentData.builder().id(id).status(state).issueDate(LocalDate.of(2026,11,8)).build(); }

  @Test void returnsBinaryPdfWithIdentityAndActualState() throws Exception {
    when(sales.send("20123456786",id,"TICKET_80")).thenReturn(new SaleV2Service.Result(document("SUBMITTED"),"%PDF-".getBytes()));
    mvc.perform(post(url)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
            .andExpect(header().string("X-Document-Id",id.toString())).andExpect(header().string("X-Emission-Status","SUBMITTED"))
            .andExpect(content().bytes("%PDF-".getBytes()));
  }

  @Test void pendingReturns202WithSameIdentityAndLocation() throws Exception {
    when(sales.send("20123456786",id,"TICKET_80")).thenReturn(new SaleV2Service.Result(document("DRAFT"),null));
    mvc.perform(post(url)).andExpect(status().isAccepted()).andExpect(jsonPath("$.documentId").value(id.toString()))
            .andExpect(jsonPath("$.document.emissionStatus").value("DRAFT")).andExpect(header().string("Retry-After","2"))
            .andExpect(header().string("Location","/v2/companies/20123456786/sales/"+id));
  }

  @ParameterizedTest @ValueSource(strings={"REJECTED","CANCELLED"})
  void terminalFailuresAreNotPending(String state) throws Exception {
    when(sales.send("20123456786",id,"TICKET_80")).thenReturn(new SaleV2Service.Result(document(state),null));
    mvc.perform(post(url)).andExpect(status().isConflict()).andExpect(jsonPath("$.document.emissionStatus").value(state));
  }

  @Test void directSaleDoesNotRequireSendOrConfirmation() throws Exception {
    String body = new org.springframework.core.io.ClassPathResource("receipts/cash/request.json")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    when(sales.save(eq("20123456786"),eq("direct-sale-key"),any(),eq(true),eq("TICKET_80")))
            .thenReturn(new SaleV2Service.Result(document("SUBMITTED"),"%PDF-".getBytes()));
    mvc.perform(post("/v2/companies/20123456786/sales").header("Idempotency-Key","direct-sale-key")
            .contentType("application/json").content("{\"document\":"+body+"}"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    verify(sales).save(eq("20123456786"),eq("direct-sale-key"),any(),eq(true),eq("TICKET_80"));
  }

  @Test void manualReviewIsNotAnEndlessPendingResponse() throws Exception {
    var doc = document("UNKNOWN");
    doc.setCommunication(pe.invoicing.app.service.document.models.CommunicationState.builder().status("MANUAL_REVIEW").build());
    when(sales.send("20123456786",id,"TICKET_80")).thenReturn(new SaleV2Service.Result(doc,null));
    mvc.perform(post(url)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("La emisión requiere revisión. Consulta el motivo antes de reintentar."));
  }

  @Test void localStatusDoesNotSend() throws Exception {
    when(sales.status("20123456786",id)).thenReturn(new SaleV2Service.Result(document("HELD"),null));
    mvc.perform(get(url.replace("/recover",""))).andExpect(status().isOk()).andExpect(jsonPath("$.emissionStatus").value("HELD"));
    verify(sales,never()).send(any(),any(),any());
  }
}
