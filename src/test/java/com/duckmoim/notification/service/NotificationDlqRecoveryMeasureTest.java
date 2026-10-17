package com.duckmoim.notification.service;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationOutbox;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.common.infra.NotificationOutboxRepository;
import com.duckmoim.notification.infra.NotificationPushSender;
import com.duckmoim.notification.infra.PermanentPushException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C 측정 — 푸시 장애로 DLQ 에 쌓인 알림을 장애가 끝난 뒤 몇 건이나 다시 전달할 수 있는가.
 *
 * <p>건수는 공통 0단계 가정의 피크 값에서 왔다. ① 일시 장애 30분 = 430건, ② 잘못된 VAPID 키 1시간 = 1,100건.
 *
 * <p>MEASURE=true 일 때만 돈다. 결과는 measure/C-dlq-reprocess/result-{MEASURE_LABEL}.tsv 에 쓴다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MEASURE", matches = "true")
class NotificationDlqRecoveryMeasureTest {

  private static final long RECIPIENT_ID = 98_101L;
  private static final int TRANSIENT_OUTAGE_COUNT = 430;
  private static final int PERMANENT_OUTAGE_COUNT = 1_100;
  private static final int MAX_ATTEMPTS = 3;

  /** 다음 푸시 호출이 무엇을 할지. 장애 중에는 던지고, 회복 뒤에는 성공을 센다. */
  private static final AtomicReference<Runnable> PUSH = new AtomicReference<>(() -> {});

  private static final AtomicInteger DELIVERED_PUSHES = new AtomicInteger();

  @TestConfiguration
  static class OutagePushConfig {

    @Bean
    @Primary
    NotificationPushSender pushSender() {
      return delivery -> PUSH.get().run();
    }
  }

  @Autowired private NotificationDispatchBatch notificationDispatchBatch;
  @Autowired private NotificationOutboxRepository outboxRepository;
  @Autowired private JdbcTemplate jdbc;

  private final List<String> rows = new ArrayList<>();

  @BeforeEach
  void setUp() {
    clean();
    DELIVERED_PUSHES.set(0);
  }

  @AfterEach
  void tearDown() throws IOException {
    PUSH.set(() -> {});
    clean();
    writeResult();
  }

  @DisplayName("① 일시 장애로 DLQ 에 간 알림을 장애가 끝난 뒤 다시 전달한다.")
  @Test
  void transientOutage() {
    savePending(TRANSIENT_OUTAGE_COUNT);
    PUSH.set(
        () -> {
          throw new IllegalStateException("푸시 서비스 5xx");
        });

    // 1분·5분 백오프를 기다리지 않고 next_attempt_at 을 비워 세 번을 바로 소진한다.
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      notificationDispatchBatch.dispatchPendingNotifications();
      awaitAttemptsRecorded(TRANSIENT_OUTAGE_COUNT, attempt);
      skipBackoff();
    }

    recoverAndMeasure("transient", TRANSIENT_OUTAGE_COUNT);
  }

  @DisplayName("② 영구 실패로 DLQ 에 간 알림을 설정을 고친 뒤 다시 전달한다.")
  @Test
  void permanentOutage() {
    savePending(PERMANENT_OUTAGE_COUNT);
    PUSH.set(
        () -> {
          throw new PermanentPushException("VAPID 설정이 틀렸다");
        });

    notificationDispatchBatch.dispatchPendingNotifications();
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .until(() -> dlqCount() == PERMANENT_OUTAGE_COUNT);

    recoverAndMeasure("permanent", PERMANENT_OUTAGE_COUNT);
  }

  /** 장애를 끝내고 워커를 한 주기 더 돌린 뒤 무엇이 남았는지 잰다. */
  private void recoverAndMeasure(String scenario, int total) {
    PUSH.set(DELIVERED_PUSHES::incrementAndGet);
    int dlqBeforeRecovery = dlqCount();

    notificationDispatchBatch.dispatchPendingNotifications();
    Awaitility.await()
        .pollDelay(2, TimeUnit.SECONDS)
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> true);

    rows.add(
        String.join(
            "\t",
            scenario,
            String.valueOf(total),
            String.valueOf(dlqBeforeRecovery),
            String.valueOf(inboxCount()),
            String.valueOf(DELIVERED_PUSHES.get()),
            String.valueOf(dlqCount())));
  }

  private void savePending(int count) {
    for (int i = 0; i < count; i++) {
      outboxRepository.save(
          NotificationOutbox.of(
              NotificationKind.POST_COMMENTED,
              RECIPIENT_ID,
              NotificationTarget.ofComment(10L, 1_000_000L + i)));
    }
  }

  /** 일꾼 스레드가 실패를 다 적을 때까지 기다린다. 마지막 시도면 DLQ 로 옮겨진 건까지 센다. */
  private void awaitAttemptsRecorded(int total, int attempt) {
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .until(() -> attemptsCount(attempt) + dlqCount() == total);
  }

  private void skipBackoff() {
    jdbc.update(
        "UPDATE notification_outbox SET next_attempt_at = NULL WHERE recipient_id = ?",
        RECIPIENT_ID);
  }

  private int attemptsCount(int attempt) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ? AND attempts = ?",
        Integer.class,
        RECIPIENT_ID,
        attempt);
  }

  private int dlqCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_outbox_dlq WHERE recipient_id = ?",
        Integer.class,
        RECIPIENT_ID);
  }

  private int inboxCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification WHERE recipient_id = ?", Integer.class, RECIPIENT_ID);
  }

  private void clean() {
    jdbc.update("DELETE FROM notification WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox_dlq WHERE recipient_id = ?", RECIPIENT_ID);
  }

  private void writeResult() throws IOException {
    if (rows.isEmpty()) {
      return;
    }

    String label = System.getenv().getOrDefault("MEASURE_LABEL", "before");
    Path file = Path.of("measure", "C-dlq-reprocess", "result-" + label + ".tsv");
    Files.createDirectories(file.getParent());

    if (Files.notExists(file)) {
      Files.writeString(
          file,
          "scenario\ttotal\tdlq_after_outage\tinbox\tpush_delivered_after_recovery\tdlq_left\n");
    }
    Files.writeString(file, String.join("\n", rows) + "\n", StandardOpenOption.APPEND);
  }
}
