package com.modelrag.common.dto;

import java.util.List;
public record PageResult<T>(List<T> records, long total, int page, int size) {
    public int totalPages() { return size == 0 ? 0 : (int) Math.ceil((double) total / size); }
}
