package io.github.minhyeok.coupon_prac.v1.common.exception;

import io.github.minhyeok.coupon_prac.v1.common.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CouponExceptionHandler {

    @ExceptionHandler(CouponException.class)
    public ResponseEntity<ErrorResponse> handleCouponException(CouponException exception) {
        var errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ErrorResponse.from(errorCode));
    }
}
