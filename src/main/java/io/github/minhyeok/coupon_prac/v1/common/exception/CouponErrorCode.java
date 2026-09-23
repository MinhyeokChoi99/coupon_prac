package io.github.minhyeok.coupon_prac.v1.common.exception;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
@Getter @RequiredArgsConstructor
public enum CouponErrorCode {
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰 이벤트를 찾을 수 없습니다."),
    USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "발급 가능한 사용자가 아닙니다."),
    EVENT_NOT_ISSUABLE(HttpStatus.CONFLICT, "현재 쿠폰을 발급할 수 없습니다."),
    COUPON_SOLD_OUT(HttpStatus.CONFLICT, "쿠폰이 모두 소진되었습니다."),
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급된 쿠폰이 있습니다."),
    INVENTORY_BUSY(HttpStatus.CONFLICT, "다른 발급 요청이 재고를 처리 중입니다. 재시도해주세요."),
    INVENTORY_NOT_AVAILABLE(HttpStatus.CONFLICT, "쿠폰 재고를 준비할 수 없습니다."),
    DAILY_ISSUANCE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "오늘의 쿠폰 발급 한도를 초과했습니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 사용자의 쿠폰을 찾을 수 없습니다."),
    COUPON_NOT_USABLE(HttpStatus.CONFLICT, "사용 가능한 쿠폰이 아닙니다."),
    INVALID_QR(HttpStatus.CONFLICT, "만료되었거나 유효하지 않은 QR입니다."),
    STORE_MISMATCH(HttpStatus.FORBIDDEN, "이 가게 또는 점주가 처리할 수 없는 쿠폰입니다."),
    INVALID_ORDER(HttpStatus.BAD_REQUEST, "주문금액 또는 할인 대상이 올바르지 않습니다.");
    private final HttpStatus httpStatus;
    private final String message;
}
