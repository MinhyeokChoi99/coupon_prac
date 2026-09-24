package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.CouponEvent;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.time.LocalDate;
import java.util.Optional;

/** 이미 적재된 이벤트 조회와 사전 적재 작업의 중복 방지를 담당한다. */
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {
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
