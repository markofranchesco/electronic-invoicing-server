package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import pe.invoicing.app.repository.document.*;
import pe.invoicing.app.repository.document.impl.DocumentStoreImpl;
import pe.invoicing.app.repository.document.mappers.DocumentPersistenceMapper;
import pe.invoicing.app.repository.identity.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountBoundaryCommercialTest extends AbstractServiceTest {
  @Test void sameRucInAnotherAccountDoesNotGrantAccessToTheDocumentOrItsNotes() {
    var document=issue("invoices/cash/request.json","isolated-document");
    var taxpayers=mock(TaxpayerEntityRepository.class);
    var repository=mock(DocumentEntityRepository.class);
    var mapper=Mappers.getMapper(DocumentPersistenceMapper.class);
    UUID otherAccount=UUID.randomUUID(),otherTaxpayer=UUID.randomUUID();
    when(taxpayers.companyId(ACCOUNT,COMPANY)).thenReturn(ACCOUNT);
    when(taxpayers.companyId(otherAccount,COMPANY)).thenReturn(otherTaxpayer);
    when(repository.findById(document.getId())).thenReturn(Optional.of(mapper.toEntity(document,ACCOUNT)));
    var adapter=new DocumentStoreImpl(taxpayers,CLOCK,repository,series,artifacts,mapper);
    assertThat(adapter.findDocument(ACCOUNT,COMPANY,document.getId()).getId()).isEqualTo(document.getId());
    assertThatThrownBy(()->adapter.findDocument(otherAccount,COMPANY,document.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(()->adapter.findCreditNotes(otherAccount,COMPANY,document.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(repository,never()).findByTaxpayerIdAndSourceDocumentId(otherTaxpayer,document.getId());
  }
}
