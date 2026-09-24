package io.github.minhyeok.coupon_prac.v1.coupon.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 외부 공급자 식별자와 계정 상태를 보관하는 쿠폰 사용자. */
@Entity
@Table(name = "`user`")
@Getter
public class CouponUser {
    /** JPA가 조회한 행을 엔티티로 복원할 때 사용하는 기본 생성자. 직접 생성할 때는 제공된 생성 메서드를 사용한다. */
    protected CouponUser() {}

    /** 테이블의 숫자 기본키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 로그인 공급자가 발급한 유일한 사용자 식별자. */
    @Column(nullable = false, unique = true, length = 255)
    private String providerUserId;

    /** 해당 도메인의 현재 상태. */
    @Column(nullable = false, length = 20)
    private String status;

    /** 행 생성 UTC 시각. DATETIME 정밀도에 맞춰 초 단위로 저장한다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant createdAt;

    /** 마지막 변경 UTC 시각. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant updatedAt;

    /**
     * 외부 공급자 식별자로 ACTIVE 사용자를 만든다. 중복 식별자는 DB UNIQUE 제약으로 차단한다.
     *
     * @param providerUserId 외부 로그인 공급자의 고유 식별자
     * @param now 계정 생성·수정 UTC 시각
     * @return 아직 저장되지 않은 ACTIVE 사용자
     */
    public static CouponUser active(String providerUserId, Instant now) {
        CouponUser user = new CouponUser();
        user.providerUserId = providerUserId;
        user.status = "ACTIVE";
        user.createdAt = now.truncatedTo(ChronoUnit.SECONDS);
        user.updatedAt = now.truncatedTo(ChronoUnit.SECONDS);
        return user;
    }

    /**
     * 계정 상태가 발급 자격을 허용하는지 확인한다.
     *
     * @return ACTIVE이면 true, 정지·탈퇴 등 다른 상태면 false
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
