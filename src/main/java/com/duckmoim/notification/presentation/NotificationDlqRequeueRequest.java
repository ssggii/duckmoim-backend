package com.duckmoim.notification.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * DLQ 재처리 구간. 실패 시각 기준 {@code [failedFrom, failedTo)} 다.
 *
 * <p><b>오프셋을 함께 받는다.</b> API 컨벤션의 시각 표기가 {@code +09:00} 을 붙인 ISO-8601 이고, DLQ 의 {@code failed_at} 은
 * UTC 로 저장된다. 오프셋 없이 받으면 관리자가 적은 KST 가 UTC 로 읽혀 아홉 시간 어긋난 구간이 되살아난다.
 */
public record NotificationDlqRequeueRequest(
    @Schema(description = "이 시각부터 (포함)", example = "2026-10-17T14:00:00+09:00")
        @NotNull(message = "시작 시각이 필요합니다.")
        OffsetDateTime failedFrom,
    @Schema(description = "이 시각 전까지 (제외)", example = "2026-10-17T14:30:00+09:00")
        @NotNull(message = "끝 시각이 필요합니다.")
        OffsetDateTime failedTo) {

  /** 끝이 시작보다 뒤여야 한다. 거꾸로 오면 빈 구간이라 조용히 0건이 되는데, 관리자는 그것을 「되살릴 것이 없다」로 읽는다. */
  @AssertTrue(message = "끝 시각은 시작 시각보다 뒤여야 합니다.")
  public boolean isRangeOrdered() {
    if (failedFrom == null || failedTo == null) {
      return true;
    }
    return failedFrom.isBefore(failedTo);
  }

  LocalDateTime fromInUtc() {
    return failedFrom.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  LocalDateTime toInUtc() {
    return failedTo.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
