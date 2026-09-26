package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.UserCoupon;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

import java.time.LocalDateTime;
import java.util.*;

/** 사용자 소유 쿠폰의 조회, QR 잠금 및 조건부 사용 처리를 담당한다. */
public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {
    /**
     * 동일 이벤트·사용자의 기존 쿠폰을 찾는다. 중복 요청에 같은 쿠폰을 반환하는 멱등 처리용이다.
     *
     * @param eventId 발급 이벤트 ID
     * @param userId 사용자 ID
     * @return 기존 쿠폰 또는 빈 Optional
     */
    Optional<UserCoupon> findByEventIdAndUserId(Long eventId, Long userId);

    /**
     * 쿠폰 ID와 소유자 ID를 함께 검사한다.
     *
     * @param id 사용자 쿠폰 ID
     * @param userId 소유 사용자 ID
     * @return 소유권이 일치하는 쿠폰 또는 빈 Optional
     */
    Optional<UserCoupon> findByIdAndUserId(Long id, Long userId);

    /**
     * 소유 쿠폰 행을 독점 잠가 QR 교체와 사용 처리가 겹치지 않게 한다.
     *
     * @param id 사용자 쿠폰 ID
     * @param userId 소유 사용자 ID
     * @return 잠긴 소유 쿠폰 또는 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserCoupon u where u.id = :id and u.userId = :userId")
    Optional<UserCoupon> lockOwned(Long id, Long userId);

    /**
     * 현재 QR 토큰 유니크 인덱스로 쿠폰을 찾아 독점 잠근다.
     *
     * @param token 스캔한 현재 QR UUID; 고정 쿠폰 코드가 아니다
     * @return 현재 토큰이 일치하는 쿠폰 또는 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserCoupon u where u.qrToken = :token")
    Optional<UserCoupon> lockByQrToken(UUID token);

    /**
     * 생성 시각·ID 내림차순으로 소유 쿠폰을 조회한다. 같은 초에 발급된 쿠폰도 ID로 정렬한다.
     *
     * @param userId 사용자 ID
     * @param pageable 페이지 번호와 크기
     * @return 최신 발급순 페이지
     */
    Page<UserCoupon> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /**
     * 실제 생성된 사용자 쿠폰 수를 센다. HTTP 성공 응답과 달리 멱등 재응답은 중복 집계하지 않는다.
     *
     * @param eventId 집계 이벤트 ID
     * @return DB의 사용자 쿠폰 행 수
     */
    long countByEventId(Long eventId);

    /**
     * 현재 토큰, 미사용 상태, 사용 기간, QR 만료를 다시 검사해 사용 상태로 변경한다.
     *
     * @param id 사용자 쿠폰 ID
     * @param token UUID 토큰을 변환한 16바이트 값
     * @param now 잠금 획득 후 확인한 현재 한국 시각
     * @return 사용 처리에 성공하면 1, 조건이 일치하지 않으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE user_coupon SET status = 'USED', qr_token = NULL, qr_expires_at = NULL, updated_at = :now
                    WHERE id = :id AND status = 'ISSUED' AND qr_token = :token
                      AND usable_start_time <= :now AND usable_end_time > :now AND qr_expires_at > :now
                    """,
            nativeQuery = true)
    int consume(Long id, byte[] token, LocalDateTime now);
}
