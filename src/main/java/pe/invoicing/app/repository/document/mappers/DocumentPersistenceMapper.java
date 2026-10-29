package pe.invoicing.app.repository.document.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.ValueMapping;
import pe.invoicing.app.repository.document.entity.CounterEntity;
import pe.invoicing.app.repository.document.entity.DocumentEntity;
import pe.invoicing.app.service.document.models.DocumentData;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneOffset;
import java.util.UUID;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        imports = ZoneOffset.class)
public abstract class DocumentPersistenceMapper {
  protected final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

  @Mapping(target = "taxpayer", ignore = true)
  @Mapping(target = "taxpayerId", source = "taxpayerId")
  @Mapping(target = "number", source = "document.number", defaultValue = "0L")
  @Mapping(target = "status", source = "document.apiStatus")
  @Mapping(
          target = "issueDate",
          expression = "java(document.getIssueDate().atStartOfDay().atOffset(ZoneOffset.ofHours(-5)))")
  @Mapping(target = "content", expression = "java(json.writeValueAsString(document))")
  public abstract DocumentEntity toEntity(DocumentData document, UUID taxpayerId);

  @ValueMapping(source = "01", target = "INVOICE")
  @ValueMapping(source = "03", target = "BOLETA")
  @ValueMapping(source = "07", target = "CREDIT_NOTE")
  @ValueMapping(source = "08", target = "DEBIT_NOTE")
  @ValueMapping(source = "09", target = "DESPATCH_ADVICE")
  @ValueMapping(source = MappingConstants.ANY_REMAINING, target = MappingConstants.THROW_EXCEPTION)
  public abstract CounterEntity.DocumentType toType(String value);

  public DocumentData toModel(DocumentEntity entity) {
    return json.readValue(entity.getContent(), DocumentData.class).toBuilder()
            .taxpayerId(entity.getTaxpayerId())
            .build();
  }
}
