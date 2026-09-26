package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.CouponEvent;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/** 이미 적재된 이벤트 조회와 사전 적재 작업의 중복 방지를 담당한다. */
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {
    /**
     * 이벤트 발급 기간·상태와 원본 캠페인 상태를 JOIN 한 번으로 조회한다.
     *
     * <p>발급 요청은 이벤트와 캠페인을 각각 조회하지 않는다. 이벤트 ID의 기본키와 캠페인 ID의 기본키를 연결하므로, 앱과 DB 사이
     * 왕복을 한 번으로 줄이면서 두 상태를 같은 조회 결과로 판단한다.
     *
     * @param eventId 발급 가능 여부를 확인할 쿠폰 이벤트 ID
     * @return 이벤트·캠페인 행이 함께 존재하면 발급 판단용 최소 정보, 이벤트가 없거나 참조 캠페인이 없으면 빈 Optional
     */
    @Query(
            value =
                    """
                    SELECT e.issue_start_at AS issueStartAt,
                           e.issue_end_at AS issueEndAt,
                           e.status AS eventStatus,
                           c.status AS campaignStatus
                      FROM coupon_event e
                      JOIN campaign c ON c.id = e.campaign_id
                     WHERE e.id = :eventId
                    """,
            nativeQuery = true)
    Optional<CouponEventIssuanceContext> findIssuanceContextById(
            @Param("eventId") Long eventId);

    /**
     * 캠페인·영업일 유니크 키로 이미 준비된 이벤트를 찾는다.
     *
     * @param campaignId 원본 캠페인 ID
     * @param date 한국 영업일
     * @return 해당 이벤트 또는 빈 Optional
     */
    Optional<CouponEvent> findByCampaignIdAndBusinessDate(Long campaignId, LocalDate date);

    /**
     * 재고 적재 중 이벤트 행을 독점 잠근다. 발급 경로에서는 이 잠금을 사용하지 않는다.
     *
     * @param id 잠글 이벤트 ID
     * @return 해당 이벤트 또는 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CouponEvent e where e.id = :id")
    Optional<CouponEvent> lockById(Long id);
}
