package io.vitalstream.clinical.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One page of results with enough metadata for clients to paginate. */
public record PageResult<T>(List<T> items, int page, int size, long totalElements) {

  @JsonProperty("totalPages")
  public int totalPages() {
    return size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
  }
}
