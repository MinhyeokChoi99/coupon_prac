package io.github.minhyeok.coupon_prac.v1.coupon.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.*;

/** 캠페인의 영업일별 쿠폰 행사. ACTIVE 상태로 미리 적재하고 발급 시작·종료 시각으로 요청을 허용한다. */
@Entity
@Table(name = "coupon_event")
@Getter
public class CouponEvent {
    /** JPA가 조회한 행을 엔티티로 복원할 때 사용하는 기본 생성자. 직접 생성할 때는 제공된 생성 메서드를 사용한다. */
    protected CouponEvent() {}

    /** 테이블의 숫자 기본키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 쿠폰 조건을 정의한 캠페인 ID. */
    @Column(nullable = false)
    private Long campaignId;

    /** 캠페인별 이벤트를 구분하는 한국 영업일. */
    @Column(nullable = false)
    private LocalDate businessDate;

    /** 해당 이벤트에 준비한 전체 수량. 남은 수량 카운터가 아니다. */
    @Column(nullable = false)
    private int couponQuantity;

    /** 발급 시작 한국 시각. ACTIVE 상태여도 이 시각 전에는 발급하지 않는다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime issueStartAt;

    /** 발급 종료 한국 시각. 이 시각부터 발급하지 않는다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime issueEndAt;

    /** 쿠폰 사용 가능 시작. 캠페인은 일일 시각, 이벤트·쿠폰은 한국 시각을 저장한다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime usableStartTime;

    /** 쿠폰 사용 가능 종료. 이 시각부터는 사용할 수 없다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime usableEndTime;

    /** 해당 도메인의 현재 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponEventStatus status;

    /** 행 생성 한국 시각. Java 원본 정밀도를 유지하며 DB DATETIME 저장은 기본 정밀도 처리에 맡긴다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    /** 마지막 변경 한국 시각. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;

    /**
     * 일별 수량과 발급·사용 기간을 검증해 ACTIVE 이벤트를 만든다. 재고와 같은 트랜잭션에서 저장해야 한다.
     *
     * @param campaignId 원본 캠페인 ID
     * @param date 이벤트를 구분하는 한국 영업일
     * @param quantity 전체 쿠폰 수량, 1 이상
     * @param issueStart 발급 시작 한국 시각(포함)
     * @param issueEnd 발급 종료 한국 시각(미포함); 사용 종료 이하여야 한다
     * @param usableStart 사용 시작 한국 시각(포함)
     * @param usableEnd 사용 종료 한국 시각(미포함)
     * @param now 생성·수정 한국 시각; Java에서는 소수점 이하도 그대로 유지한다
     * @return 아직 저장되지 않은 ACTIVE 이벤트
     * @throws IllegalArgumentException 수량 또는 시작/종료 기간이 유효하지 않은 경우
     */
    public static CouponEvent active(
            Long campaignId,
            LocalDate date,
            int quantity,
            LocalDateTime issueStart,
            LocalDateTime issueEnd,
            LocalDateTime usableStart,
            LocalDateTime usableEnd,
            LocalDateTime now) {
        if (quantity <= 0
                || !issueStart.isBefore(issueEnd)
                || !usableStart.isBefore(usableEnd)
                || issueEnd.isAfter(usableEnd))
            throw new IllegalArgumentException("Invalid event period or quantity");
        CouponEvent event = new CouponEvent();
        event.campaignId = campaignId;
        event.businessDate = date;
        event.couponQuantity = quantity;
        event.issueStartAt = issueStart;
        event.issueEndAt = issueEnd;
        event.usableStartTime = usableStart;
        event.usableEndTime = usableEnd;
        event.status = CouponEventStatus.ACTIVE;
        event.createdAt = now;
        event.updatedAt = now;
        return event;
    }

    /**
     * ACTIVE이며 발급 시작 이상·종료 미만인지 판정한다. 잔여 재고는 확인하지 않는다.
     *
     * @param now 검사 기준 한국 시각
     * @return 상태와 기간 조건을 만족하면 true
     */
    public boolean isIssuableAt(LocalDateTime now) {
        return status == CouponEventStatus.ACTIVE
                && !now.isBefore(issueStartAt)
                && now.isBefore(issueEndAt);
    }
}
