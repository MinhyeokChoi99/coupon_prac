package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import io.github.minhyeok.coupon_prac.v1.coupon.exception.CouponErrorCode;

import java.time.Instant;

/**
 * 쿠폰 업무 예외의 공통 HTTP 응답 본문.
 *
 * @param code CouponErrorCode의 이름
 * @param message 사용자에게 표시할 오류 설명
 * @param timestamp 응답을 생성한 UTC 시각
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    /**
     * 업무 오류 코드와 현재 UTC 시각으로 공통 오류 본문을 생성한다.
     *
     * @param errorCode 응답에 포함할 오류 코드 및 메시지
     * @return 오류 이름·메시지·생성 시각 DTO
     */
    public static ErrorResponse from(CouponErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), Instant.now());
    }
}
