package io.github.minhyeok.coupon_prac.v1.coupon.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.*;
import java.time.temporal.ChronoUnit;

/** 가게별 할인 조건과 일별 쿠폰 수량을 저장한다. 이미 발급한 쿠폰의 조건을 보존하기 위해 발급 이후 설정을 변경하지 않는다. */
@Entity
@Table(name = "campaign")
@Getter
public class Campaign {
    /** JPA가 조회한 행을 엔티티로 복원할 때 사용하는 기본 생성자. 직접 생성할 때는 제공된 생성 메서드를 사용한다. */
    protected Campaign() {}

    /** 테이블의 숫자 기본키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 행사 또는 사용 처리가 속한 가게 ID. */
    @Column(nullable = false)
    private Long storeId;

    /** 캠페인 소유 점주 ID. */
    @Column(nullable = false)
    private Long ownerId;

    /** 해당 도메인의 현재 상태. */
    @Column(nullable = false, length = 20)
    private String status;

    /** 캠페인 중단 사유. 운영자·점주 요청 또는 포인트 부족 등을 기록한다. */
    @Column(length = 20)
    private String pausedReason;

    /** 전체 주문(ALL) 또는 지정 메뉴(TARGET_MENU) 할인 구분. */
    @Column(nullable = false, length = 20)
    private String discountTargetType;

    /** 정액(AMOUNT) 또는 정률(PERCENT) 할인 구분. */
    @Column(nullable = false, length = 20)
    private String discountType;

    /** 정액 할인금액 또는 정률 할인율. */
    @Column(nullable = false)
    private int discountValue;

    /** 지정 메뉴 할인일 때만 사용하는 메뉴 ID. */
    private Long targetMenuId;

    /** 영업일마다 준비할 기본 쿠폰 수량. */
    @Column(nullable = false)
    private int issueQuantity;

    /** 쿠폰 사용 가능 시작. 캠페인은 일일 시각, 이벤트·쿠폰은 UTC 시각을 저장한다. */
    @Column(nullable = false)
    private LocalTime usableStartTime;

    /** 쿠폰 사용 가능 종료. 이 시각부터는 사용할 수 없다. */
    @Column(nullable = false)
    private LocalTime usableEndTime;

    /** 할인을 적용할 최소 주문금액. 제한이 없으면 null. */
    private Integer minOrderAmount;

    /** 쿠폰 안내 문구. 부하테스트 데이터에서는 전용 마커로 사용한다. */
    @Column(length = 500)
    private String notice;

    /** 노출 반경 설정. */
    @Column(nullable = false, length = 10)
    private String targetRadius;

    /** 노출 성별 설정. */
    @Column(nullable = false, length = 10)
    private String targetGender;

    /** 노출 연령대 설정. */
    @Column(nullable = false, length = 50)
    private String targetAgeGroups;

    /** 하루 예산 설정. 포인트 차감 자체는 이 엔티티가 수행하지 않는다. */
    @Column(nullable = false)
    private int dailyBudget;

    /** 캠페인 시작 한국 날짜. */
    @Column(nullable = false)
    private LocalDate startDate;

    /** 캠페인 종료 한국 날짜. */
    @Column(nullable = false)
    private LocalDate endDate;

    /** 행 생성 UTC 시각. DATETIME 정밀도에 맞춰 초 단위로 저장한다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant createdAt;

    /**
     * 할인 조건·수량·기간을 검증해 사전 적재용 ACTIVE 캠페인을 생성한다. DB 저장은 호출자가 수행한다.
     *
     * @param storeId 사용 가게 ID
     * @param ownerId 소유 점주 ID
     * @param quantity 영업일별 쿠폰 수량, 1 이상
     * @param usableStart 한국 시간 기준 매일 사용 시작 시각
     * @param usableEnd 매일 사용 종료 시각; 시작과 같을 수 없다
     * @param startDate 집행 시작 한국 날짜(포함)
     * @param endDate 집행 종료 한국 날짜(포함)
     * @param discountType 정액 AMOUNT 또는 정률 PERCENT
     * @param discountValue 할인 금액(원) 또는 0~100 정수 할인율
     * @param targetMenuId 대상 메뉴 ID; 전체 주문 할인이면 null
     * @param minOrderAmount 최소 주문 금액(원); 제한이 없으면 null
     * @param dailyBudget 일일 예산(포인트), 0 이상
     * @param notice 안내 문구 또는 null
     * @param now 생성 UTC 시각
     * @return 아직 저장되지 않은 ACTIVE 캠페인
     * @throws IllegalArgumentException 수량·기간·할인·예산·주문 조건이 유효하지 않은 경우
     */
    public static Campaign active(
            Long storeId,
            Long ownerId,
            int quantity,
            LocalTime usableStart,
            LocalTime usableEnd,
            LocalDate startDate,
            LocalDate endDate,
            String discountType,
            int discountValue,
            Long targetMenuId,
            Integer minOrderAmount,
            int dailyBudget,
            String notice,
            Instant now) {
        if (quantity <= 0
                || startDate.isAfter(endDate)
                || usableStart.equals(usableEnd)
                || dailyBudget < 0
                || (minOrderAmount != null && minOrderAmount < 0)
                || discountValue < 0
                || !java.util.Set.of("AMOUNT", "PERCENT").contains(discountType)
                || ("PERCENT".equals(discountType) && discountValue > 100)) {
            throw new IllegalArgumentException("Invalid campaign configuration");
        }
        Campaign campaign = new Campaign();
        campaign.storeId = storeId;
        campaign.ownerId = ownerId;
        campaign.status = "ACTIVE";
        campaign.discountTargetType = targetMenuId == null ? "ALL" : "TARGET_MENU";
        campaign.discountType = discountType;
        campaign.discountValue = discountValue;
        campaign.targetMenuId = targetMenuId;
        campaign.issueQuantity = quantity;
        campaign.usableStartTime = usableStart;
        campaign.usableEndTime = usableEnd;
        campaign.minOrderAmount = minOrderAmount;
        campaign.dailyBudget = dailyBudget;
        campaign.notice = notice;
        campaign.targetRadius = "1km";
        campaign.targetGender = "전체";
        campaign.targetAgeGroups = "전체";
        campaign.startDate = startDate;
        campaign.endDate = endDate;
        campaign.createdAt = now.truncatedTo(ChronoUnit.SECONDS);
        return campaign;
    }

    /**
     * 캠페인 상태만으로 발급 허용 여부를 확인한다. 이벤트 발급 기간은 별도 검사한다.
     *
     * @return ACTIVE이면 true, 그 외 false
     */
    public boolean allowsIssuance() {
        return status.equals("ACTIVE");
    }
}
