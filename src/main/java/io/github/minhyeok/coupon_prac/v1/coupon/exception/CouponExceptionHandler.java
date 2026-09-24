package io.github.minhyeok.coupon_prac.v1.coupon.exception;

import io.github.minhyeok.coupon_prac.v1.coupon.dto.ErrorResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 쿠폰 업무 예외를 일관된 오류 응답으로 변환한다. */
@RestControllerAdvice(basePackages = "io.github.minhyeok.coupon_prac.v1.coupon")
public class CouponExceptionHandler {
    /** Spring MVC가 쿠폰 예외 처리기를 등록할 때 사용하는 기본 생성자. */
    public CouponExceptionHandler() {}

    /**
     * 쿠폰 업무 예외를 정의된 HTTP 상태와 오류 JSON으로 변환한다.
     *
     * @param exception 서비스 또는 엔티티에서 발생한 업무 예외
     * @return 코드·메시지·UTC 응답 시각이 포함된 HTTP 응답
     */
    @ExceptionHandler(CouponException.class)
    public ResponseEntity<ErrorResponse> handleCouponException(CouponException exception) {
        CouponErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ErrorResponse.from(errorCode));
    }
}
