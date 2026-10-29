package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import pe.invoicing.app.repository.document.*;
import pe.invoicing.app.repository.document.entity.DocumentEntity;
import pe.invoicing.app.repository.document.impl.DocumentStoreImpl;
import pe.invoicing.app.repository.document.mappers.DocumentPersistenceMapper;
import pe.invoicing.app.repository.identity.*;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;
import pe.invoicing.app.service.document.models.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NoteCollectionConcurrencyTest extends AbstractServiceTest {
  @Test void changingANoteInvalidatesPreviouslyReadCollectionVersion() {
    var sale=issue("invoices/cash/request.json","note-race-original");
    sale.setVersion(7L);
    var stale=JSON.readValue(JSON.writeValueAsString(sale),DocumentData.class);
    var taxpayers=mock(TaxpayerEntityRepository.class);
    var repository=mock(DocumentEntityRepository.class);
    var mapper=Mappers.getMapper(DocumentPersistenceMapper.class);
    Map<UUID,DocumentEntity> database=new HashMap<>();
    database.put(sale.getId(),mapper.toEntity(sale,ACCOUNT));
    when(taxpayers.companyId(ACCOUNT,COMPANY)).thenReturn(ACCOUNT);
    when(taxpayers.lockCompany(ACCOUNT,COMPANY)).thenReturn(Optional.of(new TaxpayerEntity()));
    when(repository.findById(any())).thenAnswer(c->Optional.ofNullable(database.get(c.getArgument(0))));
    when(repository.saveAndFlush(any())).thenAnswer(c->{ DocumentEntity entity=c.getArgument(0); database.put(entity.getId(),entity);return entity;});
    var adapter=new DocumentStoreImpl(taxpayers,CLOCK,repository,series,artifacts,mapper);
    var note=sale.toBuilder().id(UUID.randomUUID()).documentType("07").sourceDocumentId(sale.getId()).version(null).build();
    adapter.saveDocument(ACCOUNT,note);
    assertThat(mapper.toModel(database.get(sale.getId())).getVersion()).isEqualTo(8L);
    assertThatThrownBy(()->adapter.saveDocument(ACCOUNT,stale)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    // Cancelar/aceptar posteriormente la nota también cambia la versión de la venta.
    note.setStatus("CANCELLED");
    adapter.saveDocument(ACCOUNT,note);
    assertThat(mapper.toModel(database.get(sale.getId())).getVersion()).isEqualTo(9L);
  }
}
