package pe.invoicing.app.controller.shared.dtos.responses;

import lombok.Builder;
import org.springframework.data.domain.Page;

import java.util.List;

@Builder
public record PageResponse<T>(List<T> data, Pagination pagination) {

  public PageResponse {
    data = List.copyOf(data);
  }

  public static <T> PageResponse<T> from(Page<T> source) {
    return PageResponse.<T>builder()
            .data(source.getContent())
            .pagination(
                    Pagination.builder()
                            .page(source.getNumber() + 1)
                            .perPage(source.getSize())
                            .totalPages(source.getTotalPages())
                            .totalItems(source.getTotalElements())
                            .hasNextPage(source.hasNext())
                            .hasPrevPage(source.hasPrevious())
                            .build())
            .build();
  }
}
