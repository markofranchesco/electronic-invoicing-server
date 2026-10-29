package pe.invoicing.app.controller.document.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.controller.document.dtos.requests.DocumentUpsertRequest;
import pe.invoicing.app.controller.document.dtos.shared.CustomerInput;
import pe.invoicing.app.controller.document.dtos.shared.InstallmentInput;
import pe.invoicing.app.service.document.models.Customer;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.Installment;
import pe.invoicing.app.service.document.models.Payment;

import java.util.List;
import java.util.Optional;

@Mapper(
        componentModel = "spring",
        imports = {Optional.class, List.class, Customer.class, Payment.class},
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DocumentMapper {
  @Mapping(target = "sourceDocumentId", ignore = true)
  @Mapping(target = "note", ignore = true)
  @Mapping(
          target = "customer",
          expression =
                  "java(Optional.ofNullable(toCustomer(request.getCustomer())).orElseGet(() ->"
                          + " Customer.builder().documentType(\"0\").documentNumber(\"0\").name(\"CONSUMIDOR"
                          + " FINAL\").build()))")
  @Mapping(
          target = "payment",
          expression =
                  "java(Payment.builder().type(\"Credito\".equals(request.getPaymentType()) ? \"CREDIT\" :"
                          + " \"CASH\").pendingAmount(request.getPendingAmount()).installments(Optional.ofNullable(toInstallments(request.getInstallments())).orElseGet(List::of)).build())")
  DocumentDraft toService(DocumentUpsertRequest request);

  @Mapping(target = "icbperUnitAmount", ignore = true)
  @Mapping(target = "igvRate", ignore = true)
  @Mapping(target = "originalLineId", ignore = true)
  @Mapping(target = "creditAllocation", ignore = true)
  pe.invoicing.app.service.document.models.DocumentItem toItem(
          pe.invoicing.app.controller.document.dtos.shared.DocumentItem item);

  Customer toCustomer(CustomerInput customer);

  List<Installment> toInstallments(List<InstallmentInput> installments);
}
