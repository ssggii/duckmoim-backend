package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.notification.infra.NotificationPushSender;
import com.duckmoim.notification.infra.PermanentPushException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DLQ 재처리 — 되살린 건이 워커를 지나 다시 전달되는가.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 청크마다 트랜잭션을 끊는 것과 {@code FOR UPDATE SKIP LOCKED} 가 검증 대상이라,
 * 검사가 트랜잭션을 쥐고 있으면 그 경계가 사라진다 (테스트 규칙: 동시성 테스트에 트랜잭션을 쓰지 않는다).
 */
@SpringBootTest
class NotificationDlqRequeueServiceTest {

  private static final long RECIPIENT_ID = 98_201L;
  private static final long ADMIN_ID = 1L;
  private static final LocalDateTime OUTAGE_FROM = LocalDateTime.of(2026, 10, 17, 5, 0);
  private static final LocalDateTime OUTAGE_TO = LocalDateTime.of(2026, 10, 17, 5, 30);
  private static final LocalDateTime IN_OUTAGE = LocalDateTime.of(2026, 10, 17, 5, 10);

  /** 다음 푸시 호출이 무엇을 할지. 검사마다 갈아끼운다. */
  private static final AtomicReference<Runnable> PUSH = new AtomicReference<>(() -> {});

  @TestConfiguration
  static class PushConfig {

    @Bean
    @Primary
    NotificationPushSender pushSender() {
      return delivery -> PUSH.get().run();
    }
  }

  @Autowired private NotificationDlqRequeueService requeueService;
  @Autowired private NotificationDispatchBatch notificationDispatchBatch;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    PUSH.set(() -> {});
    clean();
  }

  @AfterEach
  void tearDown() {
    PUSH.set(() -> {});
    clean();
  }

  @DisplayName("DLQ 건을 원래 번호로 아웃박스에 되살리고 DLQ 에서 지운다.")
  @Test
  void requeue_restoresWithOriginalOutboxId() {
    // given
    dlqRow(700_001L, 3, IN_OUTAGE);

    // when
    int requeued = requeueService.requeue(OUTAGE_FROM, OUTAGE_TO, ADMIN_ID);

    // then
    assertThat(requeued).isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT status, attempts, next_attempt_at FROM notification_outbox WHERE id = ?",
                700_001L))
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("next_attempt_at", null);
    assertThat(dlqCount()).isZero();
  }

  @DisplayName("알림함이 이미 있는 건을 되살리면 알림함을 다시 만들지 않고 푸시만 보낸다.")
  @Test
  void requeue_doesNotDuplicateInbox() {
    // given
    dlqRow(700_002L, 3, IN_OUTAGE);
    inboxRow(700_002L);
    requeueService.requeue(OUTAGE_FROM, OUTAGE_TO, ADMIN_ID);

    // when
    notificationDispatchBatch.dispatchPendingNotifications();

    // then
    awaitStatus(700_002L, "SENT");
    assertThat(inboxCount(700_002L)).isEqualTo(1);
  }

  @DisplayName("알림함까지 없던 건을 되살리면 알림함부터 만든다.")
  @Test
  void requeue_createsMissingInbox() {
    // given
    dlqRow(700_003L, 3, IN_OUTAGE);
    requeueService.requeue(OUTAGE_FROM, OUTAGE_TO, ADMIN_ID);

    // when
    notificationDispatchBatch.dispatchPendingNotifications();

    // then
    awaitStatus(700_003L, "SENT");
    assertThat(inboxCount(700_003L)).isEqualTo(1);
  }

  @DisplayName("실패 시각이 구간 밖인 건은 되살리지 않는다.")
  @Test
  void requeue_ignoresOutsideRange() {
    // given
    dlqRow(700_004L, 3, OUTAGE_FROM.minusSeconds(1));
    dlqRow(700_005L, 3, OUTAGE_TO);

    // when
    int requeued = requeueService.requeue(OUTAGE_FROM, OUTAGE_TO, ADMIN_ID);

    // then
    assertThat(requeued).isZero();
    assertThat(dlqCount()).isEqualTo(2);
  }

  @DisplayName("되살린 건이 다시 실패하면 DLQ 로 돌아온다.")
  @Test
  void requeue_returnsToDlqWhenFailsAgain() {
    // given
    dlqRow(700_006L, 1, IN_OUTAGE);
    requeueService.requeue(OUTAGE_FROM, OUTAGE_TO, ADMIN_ID);
    PUSH.set(
        () -> {
          throw new PermanentPushException("VAPID 설정이 아직 틀렸다");
        });

    // when
    notificationDispatchBatch.dispatchPendingNotifications();

    // then
    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> dlqCount() == 1);
    assertThat(statusOf(700_006L)).isEqualTo("없음");
  }

  @DisplayName("관리자 둘이 같은 구간을 동시에 재처리해도 한 건은 한 번만 되살아난다.")
  @Test
  void requeue_concurrentAdminsRestoreEachRowOnce() throws Exception {
    // given
    int total = 450;
    for (int i = 0; i < total; i++) {
      dlqRow(710_000L + i, 3, IN_OUTAGE);
    }
    CountDownLatch start = new CountDownLatch(1);
    Callable<Integer> admin =
        () -> {
          start.await();
          return requeueService.requeue(OUTAGE_FROM, OUTAGE_TO, ADMIN_ID);
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);

    // when
    List<Future<Integer>> results = List.of(pool.submit(admin), pool.submit(admin));
    start.countDown();
    int requeued =
        results.get(0).get(30, TimeUnit.SECONDS) + results.get(1).get(30, TimeUnit.SECONDS);
    pool.shutdown();

    // then
    assertThat(requeued).isEqualTo(total);
    assertThat(outboxCount()).isEqualTo(total);
    assertThat(dlqCount()).isZero();
  }

  private void dlqRow(long outboxId, int attempts, LocalDateTime failedAt) {
    jdbc.update(
        """
        INSERT INTO notification_outbox_dlq
            (outbox_id, recipient_id, kind, post_id, comment_id, attempts, failed_at,
             created_at, updated_at)
        VALUES (?, ?, 'POST_COMMENTED', 10, ?, ?, ?, ?, ?)
        """,
        outboxId,
        RECIPIENT_ID,
        outboxId,
        attempts,
        failedAt,
        failedAt,
        failedAt);
  }

  private void inboxRow(long outboxId) {
    jdbc.update(
        """
        INSERT INTO notification
            (outbox_id, recipient_id, kind, post_id, comment_id, created_at, updated_at)
        VALUES (?, ?, 'POST_COMMENTED', 10, ?, ?, ?)
        """,
        outboxId,
        RECIPIENT_ID,
        outboxId,
        IN_OUTAGE,
        IN_OUTAGE);
  }

  private void awaitStatus(long outboxId, String status) {
    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> status.equals(statusOf(outboxId)));
  }

  private String statusOf(long outboxId) {
    List<String> found =
        jdbc.queryForList(
            "SELECT status FROM notification_outbox WHERE id = ?", String.class, outboxId);

    return found.isEmpty() ? "없음" : found.get(0);
  }

  private int inboxCount(long outboxId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification WHERE outbox_id = ?", Integer.class, outboxId);
  }

  private int outboxCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ?",
        Integer.class,
        RECIPIENT_ID);
  }

  private int dlqCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_outbox_dlq WHERE recipient_id = ?",
        Integer.class,
        RECIPIENT_ID);
  }

  private void clean() {
    jdbc.update("DELETE FROM notification WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox_dlq WHERE recipient_id = ?", RECIPIENT_ID);
  }
}
