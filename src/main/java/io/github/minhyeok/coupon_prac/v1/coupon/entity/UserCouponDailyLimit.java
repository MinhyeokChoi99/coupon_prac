package io.github.minhyeok.coupon_prac.v1.coupon.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.*;

/** 사용자 한 명의 한국 날짜별 발급 횟수. limitDate는 한국 생성 시각의 날짜를 DB가 계산한다. */
@Entity
@Table(name = "coupon_daily_limit")
@Getter
public class UserCouponDailyLimit {
    /** JPA가 조회한 행을 엔티티로 복원할 때 사용하는 기본 생성자. 한도 행 생성은 저장소의 createIfAbsent SQL로만 수행한다. */
    protected UserCouponDailyLimit() {}

    /** 테이블의 숫자 기본키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 쿠폰을 소유하거나 일일 한도가 적용되는 사용자 ID. */
    @Column(nullable = false)
    private Long userId;

    /** 해당 사용자가 그날 발급받은 수량. 최대 3. */
    @Column(nullable = false)
    private int issuedCount;

    /** createdAt에서 계산되는 한국 날짜. 애플리케이션이 직접 INSERT·UPDATE하지 않는다. */
    @Column(insertable = false, updatable = false)
    private LocalDate limitDate;

    /** 행 생성 한국 시각. Java 원본 정밀도를 유지하며 DB DATETIME 저장은 기본 정밀도 처리에 맡긴다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    /** 마지막 변경 한국 시각. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;
}
