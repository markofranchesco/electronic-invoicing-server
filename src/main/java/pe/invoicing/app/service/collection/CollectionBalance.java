package pe.invoicing.app.service.collection;

import java.util.List;

public record CollectionBalance(String currency, String originalTotal, String creditedAmount, String creditedToCustomer, String detractionAmount, String debitedAmount,
        String initialPayment, String collectedAmount, String outstandingAmount,
        String refundableAmount, List<CollectionEntry> entries) { }
