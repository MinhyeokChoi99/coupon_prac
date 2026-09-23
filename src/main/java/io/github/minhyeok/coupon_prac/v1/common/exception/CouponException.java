package io.github.minhyeok.coupon_prac.v1.common.exception;

public class CouponException extends RuntimeException {

    private final CouponErrorCode errorCode;

    public CouponException(CouponErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public CouponErrorCode getErrorCode() {
        return errorCode;
    }
}
