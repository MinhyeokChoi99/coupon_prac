package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * 점주가 QR 사용을 요청할 때 전달하는 연습용 입력. 운영에서는 권한·주문 금액을 서버에서 검증해야 한다.
 *
 * @param qrToken 스캔한 QR UUID
 * @param storeId 사용 가게 ID, 필수 양수
 * @param ownerId 요청 점주 ID, 필수 양수
 * @param orderAmount 전체 주문 금액(원), 양수
 * @param menuId 특정 메뉴 할인 대상 ID; 전체 할인에서는 null 허용
 * @param menuAmount 대상 메뉴 주문 금액(원); 전체 할인에서는 null 허용
 */
public record UseRequest(
        @NotNull UUID qrToken,
        @NotNull @Positive Long storeId,
        @NotNull @Positive Long ownerId,
        @Positive int orderAmount,
        @Positive Long menuId,
        @Positive Integer menuAmount) {}
