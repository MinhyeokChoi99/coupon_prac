package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.CouponInventory;
import io.github.minhyeok.coupon_prac.v1.coupon.entity.CouponInventoryStatus;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 재고 조회와 쿠폰 한 행 단위의 동시성 제어를 제공한다. */
public interface CouponInventoryRepository extends JpaRepository<CouponInventory, Long> {
    /**
     * 커밋된 재고의 존재를 비잠금 조회한다. 발급 실패 롤백 후 AVAILABLE이 남으면 잠금 경합으로 해석한다.
     *
     * @param eventId 이벤트 ID
     * @param status 확인할 재고 상태
     * @return 조건에 맞는 재고가 하나라도 있으면 true
     */
    boolean existsByEventIdAndStatus(Long eventId, CouponInventoryStatus status);

    /**
     * 이벤트 전체 재고 수를 센다. 적재 완성 여부를 확인하며 잔여 수량 카운터를 갱신하지 않는다.
     *
     * @param eventId 이벤트 ID
     * @return 발급 여부와 무관한 재고 행 수
     */
    long countByEventId(Long eventId);

    /**
     * 특정 상태의 재고 수를 조회해 발급·롤백 결과를 검증한다.
     *
     * @param eventId 이벤트 ID
     * @param status 집계 상태
     * @return 조건에 맞는 행 수
     */
    long countByEventIdAndStatus(Long eventId, CouponInventoryStatus status);

    /**
     * 상태별 재고를 적재 순번 오름차순으로 페이지 조회한다.
     *
     * @param eventId 이벤트 ID
     * @param status 조회 상태
     * @param pageable 페이지 번호와 크기
     * @return 순번으로 정렬한 재고 페이지
     */
    Page<CouponInventory> findByEventIdAndStatusOrderBySequenceNoAsc(
            Long eventId, CouponInventoryStatus status, Pageable pageable);

    /**
     * 다른 요청이 잠근 행을 건너뛰고 AVAILABLE 재고 한 행을 독점 선점한다.
     *
     * <p>쓰기 트랜잭션 안에서 호출한다. 빈 결과는 품절 또는 후보가 모두 잠긴 상태이므로 단독으로 품절 판정에 사용하지 않는다.
     *
     * @param eventId 발급할 이벤트 ID
     * @return 선점한 재고 또는 선점 가능한 행이 없으면 빈 Optional
     */
    @Query(
            value =
                    """
                    select *
                      from coupon_inventory
                     where event_id = :eventId
                       and status = 'AVAILABLE'
                     order by sequence_no
                     limit 1 for update skip locked
                    """,
            nativeQuery = true)
    Optional<CouponInventory> lockFirstAvailableByEventId(@Param("eventId") Long eventId);
}
