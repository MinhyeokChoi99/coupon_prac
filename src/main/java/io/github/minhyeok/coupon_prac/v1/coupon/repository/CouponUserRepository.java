package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.User;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.*;

import java.util.Optional;

/** 쿠폰 발급 자격 확인에 필요한 사용자 정보를 조회한다. */
public interface CouponUserRepository extends JpaRepository<User, Long> {
    /**
     * 발급할 사용자를 독점 잠가 동일 사용자의 동시 발급 요청을 직렬화한다.
     *
     * <p>사용자 행은 항상 존재하므로 없는 user_coupon 행을 잠글 때 생길 수 있는 인덱스 구간 잠금을 만들지 않는다. 잠금을 얻은 뒤
     * 일반 조회를 처음 수행하면 MySQL 기본 REPEATABLE_READ에서도 먼저 커밋된 동일 사용자 쿠폰을 볼 수 있다.
     *
     * @param id 잠글 사용자 ID
     * @return 잠긴 사용자 또는 존재하지 않으면 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(Long id);
}
