package io.github.minhyeok.coupon_prac.v1.common.dto;

import java.time.Instant;

import io.github.minhyeok.coupon_prac.v1.common.exception.CouponErrorCode;

public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse from(CouponErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), Instant.now());
    }
}
