package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import java.time.LocalDateTime;

/**
 * 쿠폰 발급 가능 여부를 판단하기 위해 이벤트와 캠페인에서 함께 읽는 최소 정보다.
 *
 * <p>발급 경로는 이벤트와 캠페인을 따로 조회하지 않고 JOIN 한 번으로 이 인터페이스를 채운다. JPA 엔티티가 아니라 조회 결과 전용
 * projection이므로 수정·저장에는 사용하지 않는다.
 */
public interface CouponEventIssuanceContext {
    /**
     * 이벤트의 발급 시작 한국 시각을 반환한다.
     *
     * @return 이 시각 이상부터 발급 가능한 시각
     */
    LocalDateTime getIssueStartAt();

    /**
     * 이벤트의 발급 종료 한국 시각을 반환한다.
     *
     * @return 이 시각부터 발급할 수 없는 시각
     */
    LocalDateTime getIssueEndAt();

    /**
     * 이벤트 상태 문자열을 반환한다.
     *
     * @return coupon_event.status에 저장된 상태
     */
    String getEventStatus();

    /**
     * 원본 캠페인 상태 문자열을 반환한다.
     *
     * @return campaign.status에 저장된 상태
     */
    String getCampaignStatus();

    /**
     * 이벤트·캠페인이 ACTIVE이고 현재 시각이 발급 기간 안인지 판정한다.
     *
     * @param now 한국 기준 현재 시각
     * @return 이벤트와 캠페인이 활성 상태이고 시작 이상·종료 미만이면 true
     */
    default boolean isIssuableAt(LocalDateTime now) {
        return "ACTIVE".equals(getEventStatus())
                && "ACTIVE".equals(getCampaignStatus())
                && !now.isBefore(getIssueStartAt())
                && now.isBefore(getIssueEndAt());
    }
}
