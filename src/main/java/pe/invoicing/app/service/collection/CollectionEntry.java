package pe.invoicing.app.service.collection;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionEntry(UUID id, String keyHash, String amount, String currency,
        LocalDate paidOn, String reference, OffsetDateTime registeredAt,
        OffsetDateTime reversedAt, String reversalReason) { }
