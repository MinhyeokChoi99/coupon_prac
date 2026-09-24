package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.Campaign;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;

/** 캠페인 조회 및 수동 사전 적재 시 중복 준비를 막는 잠금을 제공한다. */
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    /**
     * 같은 캠페인의 중복 이벤트 적재를 막기 위해 행을 독점 잠근다.
     *
     * @param id 잠글 캠페인 ID
     * @return 해당 캠페인 또는 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> lockById(Long id);
}
