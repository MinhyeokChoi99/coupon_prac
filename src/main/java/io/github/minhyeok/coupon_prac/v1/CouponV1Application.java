package io.github.minhyeok.coupon_prac.v1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 미리 적재된 MySQL 데이터를 사용하는 쿠폰 API 애플리케이션. */
@SpringBootApplication
public class CouponV1Application {

    /** Spring Boot가 v1 설정 클래스를 생성할 때 사용하는 기본 생성자. */
    public CouponV1Application() {}

    /**
     * v1 쿠폰 API와 v1 하위 패키지의 컴포넌트를 시작한다. 이벤트 적재·상태 전환은 자동 실행하지 않는다.
     *
     * @param args 서버 포트·DB 연결 등 Spring Boot 실행 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(CouponV1Application.class, args);
    }
}
