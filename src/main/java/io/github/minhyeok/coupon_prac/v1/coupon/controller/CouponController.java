package io.github.minhyeok.coupon_prac.v1.coupon.controller;

import io.github.minhyeok.coupon_prac.v1.coupon.dto.*;
import io.github.minhyeok.coupon_prac.v1.coupon.service.CouponService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** v1 쿠폰 발급·QR·사용·조회 API. 연습용 ID 입력이며 실제 운영에는 인증·인가가 필요하다. */
@RestController
@RequestMapping("/api/v1")
public class CouponController {
    /** v1의 적재·발급·사용·조회 업무 서비스. */
    private final CouponService service;

    /**
     * 쿠폰 업무를 담당하는 단일 서비스를 주입한다.
     *
     * @param service 적재·발급·사용·조회 규칙을 실행할 쿠폰 서비스
     */
    public CouponController(CouponService service) {
        this.service = service;
    }

    /**
     * 이벤트별 쿠폰 발급 HTTP 요청을 처리한다. 신규 발급과 멱등 재응답 모두 HTTP 201을 반환한다.
     *
     * @param eventId 경로에 전달된 쿠폰 이벤트 ID
     * @param request 발급 사용자 ID를 담은 검증된 요청 본문
     * @return 실제 커밋된 사용자 쿠폰 ID·고정 쿠폰 코드·발급 시각
     */
    @PostMapping("/coupon-events/{eventId}/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueCouponResponse issue(
            @PathVariable Long eventId, @Valid @RequestBody IssueCouponRequest request) {
        CouponIssueResult result = service.issue(new IssueCouponCommand(eventId, request.userId()));
        return IssueCouponResponse.from(result);
    }

    // Practice API: userId/ownerId must come from authenticated principals in production.
    /**
     * 사용자가 소유한 쿠폰의 QR을 생성/교체한다. 운영에서는 요청 userId를 인증 정보로 대체해야 한다.
     *
     * @param id 경로에 전달된 사용자 쿠폰 ID
     * @param request 소유 사용자 ID를 담은 요청
     * @return 새 토큰, QR 버전, 만료 시각
     */
    @PostMapping("/user-coupons/{id}/qr")
    public QrResult qr(@PathVariable @Positive Long id, @Valid @RequestBody QrRequest request) {
        return service.rotateQr(id, request.userId());
    }

    /**
     * 점주가 스캔한 QR과 주문 조건을 전달해 쿠폰을 사용 처리한다. 운영에서는 점주 권한과 주문 금액을 서버에서 검증해야 한다.
     *
     * @param request QR UUID, 가게·점주 ID, 전체/대상 메뉴 주문 금액
     * @return 1회 사용 결과와 확정 할인액
     */
    @PostMapping("/coupon-usages")
    public UseResult use(@Valid @RequestBody UseRequest request) {
        return service.use(
                request.qrToken(),
                request.storeId(),
                request.ownerId(),
                request.orderAmount(),
                request.menuId(),
                request.menuAmount());
    }

    /**
     * 사용자 쿠폰함을 최신 발급순으로 조회한다. 기본 페이지는 0, 기본 크기는 20이다.
     *
     * @param userId 경로에 전달된 사용자 ID
     * @param page 0 이상인 페이지 번호
     * @param size 1~100 사이의 페이지 크기
     * @return QR 토큰을 제외한 쿠폰 페이지
     */
    @GetMapping("/users/{userId}/coupons")
    public Page<CouponView> list(
            @PathVariable @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(userId, page, size);
    }

    /**
     * 사용자 쿠폰의 소유권을 확인하고 상세 및 사용 기록을 반환한다.
     *
     * @param id 경로에 전달된 사용자 쿠폰 ID
     * @param userId 쿼리 파라미터로 전달한 소유 사용자 ID
     * @return 쿠폰 상세 및 선택적인 사용 시각/할인액
     */
    @GetMapping("/user-coupons/{id}")
    public CouponDetail detail(
            @PathVariable @Positive Long id, @RequestParam @Positive Long userId) {
        return service.detail(id, userId);
    }
}
