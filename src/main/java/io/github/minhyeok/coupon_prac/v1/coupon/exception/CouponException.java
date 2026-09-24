package io.github.minhyeok.coupon_prac.v1.coupon.exception;

/** 업무 오류 코드를 전달하고 진행 중인 발급·사용 트랜잭션을 롤백시키는 예외. */
public class CouponException extends RuntimeException {

    /** 응답 상태와 메시지를 결정할 업무 오류 코드. */
    private final CouponErrorCode errorCode;

    /**
     * 업무 오류 메시지로 런타임 예외를 만든다. 쓰기 트랜잭션에 전달되면 롤백 대상이다.
     *
     * @param errorCode HTTP 상태와 사용자 메시지를 정의한 오류 코드
     */
    public CouponException(CouponErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * HTTP 예외 처리기가 사용할 업무 오류 코드를 반환한다.
     *
     * @return 생성 시 전달한 오류 코드
     */
    public CouponErrorCode getErrorCode() {
        return errorCode;
    }
}
