package pe.invoicing.app.service.collection;

import java.time.LocalDate;

public record CollectionRequest(String amount, String currency, LocalDate paidOn, String reference) { }
