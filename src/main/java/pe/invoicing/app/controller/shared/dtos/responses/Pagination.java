package pe.invoicing.app.controller.shared.dtos.responses;

import lombok.Builder;

@Builder
public record Pagination(
        int page,
        int perPage,
        int totalPages,
        long totalItems,
        boolean hasNextPage,
        boolean hasPrevPage) {
}
