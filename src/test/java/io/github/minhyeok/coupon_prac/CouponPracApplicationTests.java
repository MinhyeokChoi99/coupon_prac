package io.github.minhyeok.coupon_prac;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0"
)
class CouponPracApplicationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
            .withExposedPorts(6379);

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private Environment environment;

    @Test
    void connectsToMysqlThroughJpaAndToRedis() {
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            Number result = (Number) entityManager.createNativeQuery("SELECT 1").getSingleResult();
            assertThat(result.intValue()).isEqualTo(1);
        }

        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @Test
    void reportsHealthyWithDatabaseAndRedis() throws Exception {
        var response = getManagementEndpoint("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        var response = getManagementEndpoint("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    private HttpResponse<String> getManagementEndpoint(String path) throws Exception {
        int port = environment.getRequiredProperty("local.management.port", Integer.class);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

}
