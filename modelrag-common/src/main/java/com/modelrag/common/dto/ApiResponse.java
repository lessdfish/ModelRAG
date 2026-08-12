package com.modelrag.common.dto;

public record ApiResponse<T>(int code, String msg, T data, long timestamp) {
    public static <T> ApiResponse<T> success(T data) { return new ApiResponse<>(0, "success", data, System.currentTimeMillis()); }
    public static <T> ApiResponse<T> fail(int code, String msg) { return new ApiResponse<>(code, msg, null, System.currentTimeMillis()); }
}
