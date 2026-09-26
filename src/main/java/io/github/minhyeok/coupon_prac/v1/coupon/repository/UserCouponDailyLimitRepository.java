package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.UserCouponDailyLimit;

import org.springframework.data.jpa.repository.*;

import java.time.*;
import java.util.Optional;

/** 한국 날짜 기준 사용자당 하루 최대 3장의 발급 한도를 관리한다. */
public interface UserCouponDailyLimitRepository extends JpaRepository<UserCouponDailyLimit, Long> {
    /**
     * 오늘 한도 행을 만들고 이미 있으면 같은 행의 잠금을 획득한다.
     *
     * <p>중복 행의 생성 시각·횟수는 변경하지 않는다. limit_date는 한국 생성 시각에서 DB가 계산한다.
     *
     * @param userId 발급 사용자 ID
     * @param now 현재 한국 시각, 소수점 이하를 포함할 수 있는 원본 값
     * @return INSERT/UPDATE 영향 행 수; 드라이버 설정에 따라 달라지므로 발급 성공 판정에 사용하지 않는다
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO coupon_daily_limit (user_id, issued_count, created_at, updated_at)
                    VALUES (:userId, 0, :now, :now)
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    int createIfAbsent(Long userId, LocalDateTime now);

    /**
     * 한도 행의 횟수가 3 미만일 때만 1 증가시킨다. 후속 재고 선점 실패 시 함께 롤백해야 한다.
     *
     * @param userId 발급 사용자 ID
     * @param date 한도 기준 한국 날짜
     * @param now 수정 한국 시각
     * @return 증가 성공이면 1, 한도 초과 또는 행 부재이면 0
     */
    @Modifying
    @Query(
            """
            update UserCouponDailyLimit d set d.issuedCount = d.issuedCount + 1, d.updatedAt = :now
            where d.userId = :userId and d.limitDate = :date and d.issuedCount < 3
            """)
    int incrementIfBelowLimit(Long userId, LocalDate date, LocalDateTime now);

    /**
     * 사용자·한국 날짜 유니크 키로 일일 발급 횟수를 조회한다.
     *
     * @param userId 사용자 ID
     * @param date 한국 날짜
     * @return 한도 행 또는 없으면 빈 Optional
     */
    Optional<UserCouponDailyLimit> findByUserIdAndLimitDate(Long userId, LocalDate date);
}
