package com.duckmoim.auth.presentation;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.identity.domain.SignupStatus;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 위키 {@code API-설계.md} 「엔드포인트 목록」의 권한 열을 그대로 옮긴 표다. <b>표 한 행 = 여기 한 줄</b> 이므로 눈으로 대조할 수 있고, {@code
 * SecurityConfig} 를 와일드카드로 아무리 묶어도 기대값은 이 표가 정한다.
 *
 * <p>엔드포인트를 추가하는 사람은 이 표에도 한 줄을 더한다. 안 더하면 등급이 검증되지 않고, 잘못 더하면 빨간불이 난다.
 *
 * <p><b>컨트롤러가 없어도 정확하다.</b> 인가는 핸들러 탐색보다 먼저 돌아서, 통과하면 404 가 나고 막히면 401·403 이 난다. 그래서 「막혔는가」는 컨트롤러
 * 유무와 무관하게 판정된다.
 *
 * <p><b>다만 컨트롤러가 생기면 이 표가 진짜 명령을 실행한다.</b> {@code DELETE /auth/token} 이 그랬다 — 등급을 확인하려고 찌른 요청이 실제
 * 로그아웃이 되어 그 회원의 토큰을 전부 무효화했고, <b>뒤따르는 검사 열다섯 개가 401 로 무너졌다.</b> 실측했다. 그래서 {@link #statusOf} 가 요청마다
 * 회원을 새로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("엔드포인트별 권한 등급")
class EndpointGradeTest {

  private enum Grade {
    PUBLIC,
    AUTH,
    SIGNUP,
    ADMIN,
    MACHINE
  }

  /**
   * 그 회원을 관리자 화이트리스트에 등록한다 (AD-06 · 0003-관리자-인가-방식.md 「선택」).
   *
   * <p><b>관문이 관리자 여부도 DB 로 확인하게 됐다.</b> 토큰에 {@code admin: true} 를 담아도 {@code admin_accounts} 에 없으면
   * 막힌다 — 등급 표가 뜻하는 것을 실제로 검사하려면 그 표에도 넣어야 한다.
   *
   * <p>회원번호가 아니라 <b>카카오 회원번호</b>로 등록한다. `User` 애그리게이트를 건드리지 않고 판정 근거를 다른 표에 두는 것이 ADR 0003 의 선택이다.
   */
  private void grantAdmin(long userId) {
    jdbcTemplate.update(
        "INSERT INTO admin_accounts (kakao_user_id, granted_at, created_at, updated_at)"
            + " SELECT kakao_user_id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)"
            + " FROM user WHERE id = ?",
        userId);
  }

  /** 등급 조합의 {@code signupCompleted} 를 회원 행의 가입 축 상태로 옮긴다 (도메인 6장). */
  private static SignupStatus statusOf(AuthUser authUser) {
    return authUser.signupCompleted() ? SignupStatus.ACTIVE : SignupStatus.PENDING_SIGNUP_INFO;
  }

  private record Endpoint(HttpMethod method, String path, Grade grade) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  /** HOST 는 관문이 판정하지 않는다. 방장 여부는 service 가 보므로 관문에서는 SIGNUP 으로 받는다. */
  private static final List<Endpoint> MATRIX =
      List.of(
          // 인프라. 사람이 아니라 ALB 와 Alloy 가 부르고, 그래서 토큰이 없다.
          // 위키 「엔드포인트 목록」에는 /api/health 만 있다 — actuator 줄은 이 저장소가
          // 먼저 열었고 위키 반영은 별도 클론에서 따라온다 (STAR-92).
          //
          // /actuator/prometheus 는 여기 없다. @SpringBootTest 가
          // management.defaults.metrics.export.enabled=false 를 기본으로 넣어 이 컨텍스트에는
          // 그 엔드포인트가 아예 없고, 그러면 404 가 나서 PUBLIC 줄이 「막히지 않았다」로
          // 통과한다 — 인가를 하나도 검사하지 않은 채 초록불이 된다. 그 경로는
          // ActuatorExposureTest 가 수출을 되살린 컨텍스트에서 본다.
          new Endpoint(HttpMethod.GET, "/api/health", Grade.PUBLIC),
          new Endpoint(HttpMethod.GET, "/actuator/health", Grade.PUBLIC),
          // 2-1 인증
          new Endpoint(HttpMethod.POST, "/api/v1/auth/kakao", Grade.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/auth/token", Grade.PUBLIC),
          new Endpoint(HttpMethod.DELETE, "/api/v1/auth/token", Grade.AUTH),
          // 2-2 회원
          new Endpoint(HttpMethod.GET, "/api/v1/users/me", Grade.AUTH),
          new Endpoint(HttpMethod.GET, "/api/v1/users/nickname-availability", Grade.AUTH),
          new Endpoint(HttpMethod.PUT, "/api/v1/users/me/signup-info", Grade.AUTH),
          new Endpoint(HttpMethod.PATCH, "/api/v1/users/me/profile", Grade.SIGNUP),
          new Endpoint(HttpMethod.POST, "/api/v1/users/me/profile-image", Grade.SIGNUP),
          // Presigned 는 서버가 파일을 받지 않아 「올라간 것을 확인하는」 단계가 따로 필요하다 (AU-08)
          new Endpoint(HttpMethod.PUT, "/api/v1/users/me/profile-image", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/users/me/posts", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/users/me/comments", Grade.SIGNUP),
          new Endpoint(HttpMethod.DELETE, "/api/v1/users/me", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/users/9", Grade.PUBLIC),
          new Endpoint(HttpMethod.GET, "/api/v1/users/9/posts", Grade.PUBLIC),
          // 2-3 행사
          new Endpoint(HttpMethod.GET, "/api/v1/events", Grade.PUBLIC),
          new Endpoint(HttpMethod.GET, "/api/v1/events/pg_8709", Grade.PUBLIC),
          // 2-4 모집글
          new Endpoint(HttpMethod.GET, "/api/v1/posts", Grade.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/posts", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/posts/1", Grade.PUBLIC),
          new Endpoint(HttpMethod.PATCH, "/api/v1/posts/1", Grade.SIGNUP),
          // 없는 글 번호다. 클래스 각주가 말한 「진짜 명령을 실행한다」에 걸리는 경로여서 그렇게 두었다 —
          // 시드에 있는 글을 찌르면 방장이 아닌 요청자에게 도메인이 403 을 내고, 관문의 403 과 구분되지 않는다.
          // 없는 번호면 404 라 관문 통과 여부만 남는다. PATCH 는 본문이 없어 400 으로 끝나 이 문제가 없다.
          new Endpoint(HttpMethod.POST, "/api/v1/posts/404404/close", Grade.SIGNUP),
          // 2-5 댓글
          new Endpoint(HttpMethod.GET, "/api/v1/posts/1/comments", Grade.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/posts/1/comments", Grade.SIGNUP),
          new Endpoint(HttpMethod.PATCH, "/api/v1/comments/1", Grade.SIGNUP),
          new Endpoint(HttpMethod.DELETE, "/api/v1/comments/1", Grade.SIGNUP),
          // 채팅 (2차). 위키 「엔드포인트 목록」이 1차 MVP 문서라 이 줄의 정본이 아직 없다 —
          // 저장소가 먼저 열고 위키 반영은 별도 클론에서 따라온다 (STAR-92 가 actuator 줄에
          // 한 것과 같다).
          //
          // 없는 글 번호다. 시드에 있는 글을 찌르면 방장이 아닌 요청자에게 service 가 403 을
          // 내고 관문의 403 과 구분되지 않는다 — POST /posts/404404/close 와 같은 이유다.
          // 요청에 본문이 없어 사실은 그 앞에서 끝나지만, 번호를 없는 것으로 두는 편이
          // 본문이 붙는 날에도 이 줄이 계속 등급만 보게 한다.
          new Endpoint(HttpMethod.POST, "/api/v1/posts/404404/chat-room/members", Grade.SIGNUP),
          // 방 목록·상세 (CH-05 · CH-06). SIGNUP 인 것은 가입 미완료 계정이 애초에 방
          // 멤버가 될 수 없어서다 — users/me/posts 와 같은 근거.
          new Endpoint(HttpMethod.GET, "/api/v1/chat-rooms", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/chat-rooms/404404", Grade.SIGNUP),
          // 메시지 전송 (CH-07 · CH-08). 같은 SIGNUP 이지만 위 둘과 다른 줄로 걸린다 —
          // 저쪽은 GET 이라 SIGNUP_READ, 이쪽은 POST 라 SIGNUP_WRITE 다.
          // 방 멤버인지는 관문이 아니라 service 가 본다.
          new Endpoint(HttpMethod.POST, "/api/v1/chat-rooms/404404/messages", Grade.SIGNUP),
          // 목록·삭제 (CH-09 · CH-12). GET 은 SIGNUP_READ 가, DELETE 는 SIGNUP_WRITE 가 덮는다 —
          // 같은 등급인데 다른 배열이라 둘 다 표에 올린다.
          new Endpoint(HttpMethod.GET, "/api/v1/chat-rooms/404404/messages", Grade.SIGNUP),
          new Endpoint(
              HttpMethod.DELETE, "/api/v1/chat-rooms/404404/messages/404404", Grade.SIGNUP),
          // 읽은 지점 적기 (CH-13). 전송과 같은 SIGNUP_WRITE 줄이다 — 알림 읽음 처리
          // (NT-09) 가 POST 인 것과 같은 이유로 POST 이고, 같은 배열에 걸린다.
          new Endpoint(HttpMethod.POST, "/api/v1/chat-rooms/404404/read", Grade.SIGNUP),
          // 퇴장 (CH-04). 전송과 같은 SIGNUP_WRITE 줄이다. 제재 중에도 열리는 것은
          // 등급이 아니라 관문 예외라 SanctionGateTest 가 본다.
          new Endpoint(HttpMethod.DELETE, "/api/v1/chat-rooms/404404/members/me", Grade.SIGNUP),
          // 이미지 업로드 (CH-14). 발급이 POST, 확정이 PUT 이다.
          //
          // **PUT 을 SIGNUP_WRITE 에 더한 것이 이 두 줄의 본체다.** 그 전까지 배열에
          // POST · PATCH · DELETE 만 걸려 있어서 PUT 은 어느 줄에도 안 걸리고
          // anyRequest().authenticated() 로 떨어졌다 — 가입 미완료 계정에게 열린다.
          new Endpoint(HttpMethod.POST, "/api/v1/chat-rooms/404404/images", Grade.SIGNUP),
          new Endpoint(HttpMethod.PUT, "/api/v1/chat-rooms/404404/images/404404", Grade.SIGNUP),
          // 이미지 열람 (CH-15). 아래 「아직 없는 경로」 줄이 예고한 깊이에 실제로 들어온
          // 첫 경로다 — SIGNUP_READ 의 ** 가 그대로 덮는다. 읽기라 제재 인터셉터에는
          // 걸리지 않는다 (도메인 6장: 읽기가 막히는 것은 BANNED 뿐이다).
          new Endpoint(
              HttpMethod.GET, "/api/v1/chat-rooms/404404/images?messageIds=404404", Grade.SIGNUP),
          // 아직 없는 PUT 경로다. 위 fail-open 이 되돌아오면 여기서 먼저 깨진다 —
          // 경로가 없어도 등급 판정은 규칙만 보므로, 핸들러를 안 만들고 규칙을 고정한다.
          new Endpoint(HttpMethod.PUT, "/api/v1/chat-rooms/404404/nothing", Grade.SIGNUP),
          // 아직 없는 경로다. SIGNUP_READ 가 ** 라서 한 칸 더 깊어도 덮인다는 것을 못박는다
          // (PR #131 리뷰). 이 표는 손으로 유지하는 것이라 새 경로를 자동으로 잡아 주지
          // 않고, 그래서 규칙 쪽이 fail-closed 여야 한다 — CH-11 의 스트림, CH-13 의 읽은
          // 지점, CH-15 의 이미지 서명이 전부 이 깊이다. 판정이 401·403 만 보므로 핸들러가
          // 없어도 등급 검사에는 문제가 없다 (가입을 마친 계정은 404 이고 그것도 통과다).
          new Endpoint(
              HttpMethod.GET, "/api/v1/chat-rooms/404404/messages/404404/nothing", Grade.SIGNUP),
          // 2-6 신고
          new Endpoint(HttpMethod.POST, "/api/v1/reports", Grade.SIGNUP),

          // 2-10. 알림 (Notification) · 2차
          new Endpoint(HttpMethod.GET, "/api/v1/notifications", Grade.SIGNUP),
          // 없는 알림 번호다. 있는 알림을 찌르면 service 가 먼저 답해서 이 줄이 등급이 아니라
          // 본문을 보게 된다 — 채팅 초대 줄이 없는 글 번호를 쓰는 것과 같은 이유다.
          new Endpoint(HttpMethod.POST, "/api/v1/notifications/404404/read", Grade.SIGNUP),
          new Endpoint(HttpMethod.POST, "/api/v1/notifications/read", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/notifications/unread-count", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/notifications/settings", Grade.SIGNUP),
          // 본문 없이 찌른다. 이 표가 보는 것은 「막혔는가」뿐이고 검증 400 은 관문을 지났다는 뜻이다.
          new Endpoint(HttpMethod.PUT, "/api/v1/notifications/settings", Grade.SIGNUP),
          // 접두어가 /notifications 밖이라 SecurityConfig 에 따로 등록돼 있다. 그 줄이
          // 빠지면 anyRequest 로 떨어져 AUTH 로 열리는데, 이 두 행이 그것을 잡는다.
          new Endpoint(HttpMethod.POST, "/api/v1/push-subscriptions", Grade.SIGNUP),
          new Endpoint(HttpMethod.DELETE, "/api/v1/push-subscriptions", Grade.SIGNUP),
          // 2-7 백오피스
          new Endpoint(HttpMethod.GET, "/api/v1/admin/reports", Grade.ADMIN),
          new Endpoint(HttpMethod.PATCH, "/api/v1/admin/reports/1", Grade.ADMIN),
          new Endpoint(HttpMethod.GET, "/api/v1/admin/comments/1", Grade.ADMIN),
          new Endpoint(HttpMethod.POST, "/api/v1/admin/comments/1/blind", Grade.ADMIN),
          new Endpoint(HttpMethod.POST, "/api/v1/admin/users/9/sanctions", Grade.ADMIN),
          new Endpoint(HttpMethod.DELETE, "/api/v1/admin/users/9/sanctions/1", Grade.ADMIN),
          new Endpoint(HttpMethod.POST, "/api/v1/admin/users/9/purge", Grade.ADMIN),
          new Endpoint(HttpMethod.GET, "/api/v1/admin/sanctions", Grade.ADMIN),
          new Endpoint(HttpMethod.GET, "/api/v1/admin/audit-logs", Grade.ADMIN),
          // 없는 방·신고 번호다. 있는 것을 찌르면 service 가 먼저 답해서 이 줄이 등급이
          // 아니라 본문을 보게 된다
          new Endpoint(
              HttpMethod.GET,
              "/api/v1/admin/chat-rooms/404404/messages?reportId=404404",
              Grade.ADMIN),
          new Endpoint(HttpMethod.POST, "/api/v1/admin/messages/404404/blind", Grade.ADMIN),
          // DLQ 재처리. 본문 없이 찌른다 — 관리자는 검증 400 을 받고, 그것이 관문을 지났다는 뜻이다
          new Endpoint(HttpMethod.POST, "/api/v1/admin/notifications/dlq/requeue", Grade.ADMIN),
          // 2-8 적재
          new Endpoint(HttpMethod.POST, "/api/v1/ingest/events/bulk", Grade.MACHINE));

  private static final AuthUser SIGNUP_INCOMPLETE = new AuthUser(1L, false, false);
  private static final AuthUser SIGNUP_COMPLETED = new AuthUser(2L, true, false);
  private static final AuthUser ADMINISTRATOR = new AuthUser(3L, true, true);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Value("${duckmoim.ingest.key}")
  private String ingestKey;

  private static Stream<Endpoint> publicEndpoints() {
    return endpointsOf(Grade.PUBLIC);
  }

  private static Stream<Endpoint> authEndpoints() {
    return endpointsOf(Grade.AUTH);
  }

  private static Stream<Endpoint> signupEndpoints() {
    return endpointsOf(Grade.SIGNUP);
  }

  private static Stream<Endpoint> adminEndpoints() {
    return endpointsOf(Grade.ADMIN);
  }

  private static Stream<Endpoint> machineEndpoints() {
    return endpointsOf(Grade.MACHINE);
  }

  /** 적재 등급이 아닌데 인가를 요구하는 경로 전부. 즉 적재 키가 절대 통하면 안 되는 자리다. */
  private static Stream<Endpoint> nonMachineProtectedEndpoints() {
    return MATRIX.stream()
        .filter(endpoint -> endpoint.grade() != Grade.MACHINE && endpoint.grade() != Grade.PUBLIC);
  }

  private static Stream<Endpoint> endpointsOf(Grade grade) {
    return MATRIX.stream().filter(endpoint -> endpoint.grade() == grade);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("publicEndpoints")
  @DisplayName("PUBLIC 경로는 토큰이 없어도 인가를 통과한다.")
  void publicPassesWithoutToken(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isNotIn(401, 403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authEndpoints")
  @DisplayName("AUTH 경로는 토큰이 없으면 401 이다.")
  void authRejectsAnonymous(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authEndpoints")
  @DisplayName("AUTH 경로는 가입 미완료 계정도 인가를 통과한다.")
  void authPassesSignupIncomplete(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_INCOMPLETE)).isNotIn(401, 403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signupEndpoints")
  @DisplayName("SIGNUP 경로는 토큰이 없으면 401 이다.")
  void signupRejectsAnonymous(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signupEndpoints")
  @DisplayName("SIGNUP 경로는 가입 미완료 계정을 403 으로 막는다.")
  void signupRejectsSignupIncomplete(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_INCOMPLETE)).isEqualTo(403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signupEndpoints")
  @DisplayName("SIGNUP 경로는 가입을 마친 계정이 인가를 통과한다.")
  void signupPassesSignupCompleted(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_COMPLETED)).isNotIn(401, 403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adminEndpoints")
  @DisplayName("ADMIN 경로는 토큰이 없으면 401 이다.")
  void adminRejectsAnonymous(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adminEndpoints")
  @DisplayName("ADMIN 경로는 일반 계정을 403 으로 막는다.")
  void adminRejectsNormalAccount(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_COMPLETED)).isEqualTo(403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adminEndpoints")
  @DisplayName("ADMIN 경로는 관리자가 인가를 통과한다.")
  void adminPassesAdministrator(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, ADMINISTRATOR)).isNotIn(401, 403);
  }

  /**
   * 적재 경로의 인가 조합 (D-11).
   *
   * <p>다른 등급과 달리 <b>토큰이 아니라 정적 키로 판정한다.</b> 그래서 {@link #statusOf} 를 쓰지 않고 헤더를 직접 붙인다 — 크롤러에게는 카카오
   * 회원번호가 없다.
   *
   * <p>넷을 모두 본다. 사람 토큰이 막히는 줄이 <b>{@code /admin} 과 인가가 실제로 갈렸는지</b>를 증명하는 자리다 — 두 등급이 섞여 있으면 관리자
   * 토큰으로 적재가 통과한다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("machineEndpoints")
  @DisplayName("적재 경로는 키가 없으면 401 이다.")
  void machineRejectsAnonymous(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("machineEndpoints")
  @DisplayName("적재 경로는 틀린 키를 401 로 막는다.")
  void machineRejectsWrongKey(Endpoint endpoint) throws Exception {
    assertThat(statusOfWithKey(endpoint, "wrong-key")).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("machineEndpoints")
  @DisplayName("적재 경로는 관리자 토큰도 403 으로 막는다.")
  void machineRejectsAdministrator(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, ADMINISTRATOR)).isEqualTo(403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("machineEndpoints")
  @DisplayName("적재 경로는 키가 맞으면 인가를 통과한다.")
  void machinePassesWithKey(Endpoint endpoint) throws Exception {
    assertThat(statusOfWithKey(endpoint, ingestKey)).isNotIn(401, 403);
  }

  /**
   * 적재 키만 붙여 요청 하나를 보낸다.
   *
   * <p>본문을 싣지 않으므로 인가를 통과하면 400 이 난다. 이 표가 보는 것은 <b>막혔는가</b>뿐이라 그걸로 충분하다.
   */
  private int statusOfWithKey(Endpoint endpoint, String key) throws Exception {
    return mockMvc
        .perform(request(endpoint.method(), endpoint.path()).header("X-Ingest-Key", key))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /**
   * 적재 키가 적재 경로 밖에서는 아무 힘이 없는지 본다.
   *
   * <p>위 넷은 적재 경로 <b>위에서만</b> 보므로, 키를 검사하는 필터가 경로를 가리지 않아도 전부 초록불이 난다. 실측했다 — 키만 붙인 {@code GET
   * /users/nickname-availability} 가 200 이었다. 필터가 {@code MACHINE} 을 붙이고 {@code
   * anyRequest().authenticated()} 가 그것을 사람으로 받아들여서다.
   *
   * <p>키가 노출되면 적재 하나가 아니라 API 전체가 열리는 문제라, 등급 표와 별도로 못박는다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("nonMachineProtectedEndpoints")
  @DisplayName("적재 키는 적재 경로 밖에서는 401 이다.")
  void ingestKeyDoesNotPassOtherGrades(Endpoint endpoint) throws Exception {
    assertThat(statusOfWithKey(endpoint, ingestKey)).isEqualTo(401);
  }

  /**
   * 표에 없는 경로가 열려 있지 않은지 본다.
   *
   * <p>위 매트릭스는 <b>선언한 경로</b>만 검사하므로, 여는 쪽 와일드카드를 {@code /api/v1/posts/**} 처럼 다시 넓혀도 초록불이 난다. 실측했다.
   * 그래서 「아직 없는 경로」를 직접 찔러 <b>기본이 닫힘</b>인 것을 못박는다.
   *
   * <p>여기 적힌 주소는 실제 엔드포인트가 아니라 <b>앞으로 생길 수 있는 자리</b>다. 그 자리에 컨트롤러가 붙는 날 등급을 정해 위 표에 올리게 만드는 것이 이
   * 테스트의 목적이다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/posts/1/participants",
        "/api/v1/posts/1/secret-notes",
        "/api/v1/events/1/attendees",
        "/api/v1/users/9/comments",
        "/api/v1/comments/1/reactions"
      })
  @DisplayName("표에 선언하지 않은 경로는 토큰이 없으면 401 이다.")
  void undeclaredPathStaysClosed(String path) throws Exception {
    assertThat(statusOf(new Endpoint(HttpMethod.GET, path, Grade.PUBLIC), null)).isEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/auth/admin-token", "/api/v1/posts/1/participants"})
  @DisplayName("표에 선언하지 않은 쓰기 경로는 토큰이 없으면 401 이다.")
  void undeclaredWritePathStaysClosed(String path) throws Exception {
    assertThat(statusOf(new Endpoint(HttpMethod.POST, path, Grade.PUBLIC), null)).isEqualTo(401);
  }

  /**
   * 요청 하나를 보내고 상태 코드만 돌려준다.
   *
   * <p><b>회원을 매번 새로 만든다.</b> 위 상수 셋은 이제 회원번호가 아니라 <b>등급 조합</b>만 뜻한다. 같은 회원을 돌려쓰면 명령을 실행하는 엔드포인트 하나가
   * 그 회원의 상태를 바꿔 뒤따르는 검사를 오염시킨다.
   *
   * <p><b>가입 상태를 DB 에도 맞춘다.</b> 관문이 가입 완료 여부를 토큰이 아니라 회원 행에서 읽게 됐다 (I-02 · AU-07) — 토큰에 무엇을 담아도 DB
   * 가 이긴다. 상수의 {@code signupCompleted} 를 회원 행의 {@code status} 로 옮겨야 이 표가 뜻하는 것을 실제로 검사한다.
   */
  private int statusOf(Endpoint endpoint, AuthUser authUser) throws Exception {
    HttpHeaders headers = new HttpHeaders();

    if (authUser != null) {
      long userId = aUser().status(statusOf(authUser)).insert(jdbcTemplate);

      if (authUser.admin()) {
        grantAdmin(userId);
      }

      headers.setBearerAuth(
          tokenProvider.createAccessToken(
              new AuthUser(userId, authUser.signupCompleted(), authUser.admin())));
    }
    return mockMvc
        .perform(request(endpoint.method(), endpoint.path()).headers(headers))
        .andReturn()
        .getResponse()
        .getStatus();
  }
}
