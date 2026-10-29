package pe.invoicing.app.service.document.models;

import lombok.Builder;
import pe.invoicing.app.service.company.models.Address;

/** Datos del local de emisión conservados en el comprobante; no impone un máximo de locales. */
@Builder
public record Establishment(String code, String name, Address address) { }
