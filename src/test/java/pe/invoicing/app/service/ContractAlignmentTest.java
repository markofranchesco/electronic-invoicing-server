package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.yaml.snakeyaml.Yaml;
import pe.invoicing.app.controller.authentication.AutenticacionController;
import pe.invoicing.app.controller.session.SessionController;
import pe.invoicing.app.controller.company.CompanyController;
import pe.invoicing.app.controller.document.*;
import pe.invoicing.app.controller.creditnote.CreditNoteController;
import pe.invoicing.app.controller.debitnote.DebitNoteController;
import pe.invoicing.app.controller.cancellation.CancellationController;
import pe.invoicing.app.controller.collection.CollectionController;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContractAlignmentTest {
  @SuppressWarnings("unchecked")
  private Map<String,Object> contract() throws Exception { return new Yaml().load(Files.readString(Path.of("electronic-invoicing.yaml"))); }

  @Test @SuppressWarnings("unchecked")
  void everyHttpRouteAndStatusIsInThePublishedContract() throws Exception {
    var paths=(Map<String,Map<String,Object>>)contract().get("paths");
    Set<String> actual=new HashSet<>();
    for(var type:List.of(AutenticacionController.class,SessionController.class,CompanyController.class,DocumentController.class,
            SaleV2Controller.class,PublicationController.class,CreditNoteController.class,DebitNoteController.class,CancellationController.class,CollectionController.class)) {
      var root=AnnotatedElementUtils.findMergedAnnotation(type,RequestMapping.class);
      String prefix=root==null?"":root.value()[0];
      for(var method:type.getDeclaredMethods()) {
        var mapping=AnnotatedElementUtils.findMergedAnnotation(method,RequestMapping.class);
        if(mapping==null)continue;
        String path=prefix+(mapping.value().length==0?"":mapping.value()[0]);
        String verb=mapping.method()[0].name().toLowerCase();
        actual.add(verb+" "+path);
        assertThat(paths).as(method.toString()).containsKey(path);
        var operation=(Map<String,Object>)paths.get(path).get(verb);
        assertThat(operation).as(verb+" "+path).isNotNull();
        var status=method.getAnnotation(ResponseStatus.class);
        assertThat((Map<String,Object>)operation.get("responses")).containsKey(status==null?"200":Integer.toString(status.value().value()));
      }
    }
    Set<String> declared=new HashSet<>();
    paths.forEach((path,verbs)->verbs.keySet().stream().filter(v->Set.of("get","post","put","delete").contains(v)).forEach(v->declared.add(v+" "+path)));
    assertThat(declared).containsExactlyInAnyOrderElementsOf(actual);
  }

  @Test @SuppressWarnings("unchecked")
  void requestAndResponseFieldsMatchPublishedSchemas() throws Exception {
    var components=(Map<String,Object>)contract().get("components");
    var schemas=(Map<String,Map<String,Object>>)components.get("schemas");
    for(var type:List.of(pe.invoicing.app.service.document.impl.PublicationService.PublicStatus.class,SaleV2Controller.SaleOperationResponse.class,SaleV2Controller.SaleRequest.class,pe.invoicing.app.controller.company.dtos.requests.CompanyUpsertRequest.class,pe.invoicing.app.controller.document.dtos.requests.DocumentUpsertRequest.class,
            pe.invoicing.app.controller.document.dtos.responses.DocumentApiResponse.class,
            pe.invoicing.app.controller.document.dtos.shared.DocumentItem.class,
            pe.invoicing.app.controller.creditnote.dtos.CreditNoteIssueRequest.class,
            pe.invoicing.app.service.debitnote.DebitNoteDraft.class,
            pe.invoicing.app.service.collection.CollectionRequest.class,
            pe.invoicing.app.service.collection.CollectionBalance.class,
            pe.invoicing.app.service.collection.CollectionEntry.class,
            pe.invoicing.app.controller.cancellation.dtos.CancellationApiResponse.class,
            pe.invoicing.app.controller.company.dtos.responses.CompanyResponse.class)) {
      var fields=Arrays.stream(type.getDeclaredFields()).filter(f->!java.lang.reflect.Modifier.isStatic(f.getModifiers()))
              .map(java.lang.reflect.Field::getName).toList();
      assertThat(((Map<String,Object>)schemas.get(type.getSimpleName()).get("properties")).keySet())
              .as(type.getSimpleName()).containsExactlyInAnyOrderElementsOf(fields);
    }
  }
}
