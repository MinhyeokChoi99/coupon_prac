package io.github.minhyeok.coupon_prac.v1.couponinventory.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import io.github.minhyeok.coupon_prac.v1.couponinventory.entity.CouponInventory;
import io.github.minhyeok.coupon_prac.v1.couponinventory.entity.CouponInventoryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponInventoryRepository extends JpaRepository<CouponInventory, Long> {

    boolean existsByEventIdAndStatus(Long eventId, CouponInventoryStatus status);

    long countByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, CouponInventoryStatus status);

    long countByEventIdIn(Collection<Long> eventIds);

    long countByEventIdInAndStatus(Collection<Long> eventIds, CouponInventoryStatus status);

    Page<CouponInventory> findByEventIdAndStatusOrderBySequenceNoAsc(
            Long eventId,
            CouponInventoryStatus status,
            Pageable pageable
    );

    Optional<CouponInventory> findByIdAndEventId(Long id, Long eventId);

    @Query(value = """
            select *
              from coupon_inventory
             where event_id = :eventId
               and status = 'AVAILABLE'
             order by sequence_no
             limit 1 for update skip locked
            """, nativeQuery = true)
    Optional<CouponInventory> lockFirstAvailableByEventId(@Param("eventId") Long eventId);

    @Modifying
    long deleteByEventId(Long eventId);
}
