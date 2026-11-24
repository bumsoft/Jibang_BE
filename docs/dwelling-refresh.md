# 주거 데이터 월별 갱신

주거 배치만 공통 `SEED_VERSION`과 수동 `DEALYMD` 설정에서 분리한다.
지역·인구·직종·일자리·인프라의 기존 적재 방식은 유지한다.

## 실행 조건

- 앱 시작 시 기존 지역 코드 적재 순서 다음에 주거 갱신 여부를 확인한다.
- 한국 시간 기준 이번 달에 `dwellingJob`이 `COMPLETED`로 종료된 이력이 있으면 유지한다.
  기존 `seedVersion` 방식으로 실행한 성공 이력도 인정한다.
- 이번 달 성공 이력이 없으면 실행한다. 실패한 실행은 완료로 인정하지 않는다.
- 이후 기본 스케줄은 **매월 1일 03:00, Asia/Seoul**이다.
- 수집 종료 월은 실행 월의 직전 월이고, 조회 기간은 해당 월을 포함한 12개월이다.
  예: 2026년 12월 실행 → 2025년 12월~2026년 11월.
- 실행 중인 주거 Job이 있으면 중복 실행하지 않는다.

새 JobParameters는 `refreshMonth=yyyyMM`, `dealYmd=yyyyMM`, `months=12`이다.
매번 다른 시각을 넣던 `triggerTime`과 `seedVersion`은 주거 Job에서 제거한다.
같은 달에는 동일한 JobInstance를 사용해 실패 실행을 재시작하고,
동시 실행은 JobRepository의 실행 상태 검사로 거부한다.

## 설정

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DWELLING_REFRESH_ENABLED` | `true` | 주거 시작 시 갱신과 월별 스케줄 활성화 |
| `DWELLING_REFRESH_CRON` | `0 0 3 1 * *` | 한국 시간 기준 스케줄 |

`DEALYMD`는 더 이상 필요하지 않다. `SEED_VERSION`은 다른 배치를 위해 유지한다.
`app.batch.enabled=false`도 주거 Runner의 등록을 차단한다.
Batch의 시각 메타데이터는 JVM 시간대를 사용하므로 운영 Dockerfile의
`-Duser.timezone=Asia/Seoul` 설정을 유지한다.

## 실패와 재실행

- 통신 오류, timeout, HTTP 5xx, HTTP 429는 기존 청크의 fault-tolerant 정책에 따라
  지역 작업 항목당 최초 시도를 포함해 최대 3회 시도하며 재시도 사이에 1초 대기한다.
- HTTP 200이어도 API 오류 코드, 빈 응답, 파싱 실패, 잘못된 응답 구조는 실패로 처리한다.
  정상 성공 코드와 `totalCount=0`인 응답만 정상적인 빈 데이터로 인정한다.
- 재시도를 소진하거나 재시도 대상이 아닌 오류가 나면 Job은 `FAILED`로 남는다.
  별도 일일 재실행은 없으며 앱 재시작 또는 다음 정기 스케줄에 다시 판단한다.
- 청크 10개(시군구 10개 작업) 단위로 커밋하므로 이전 청크는 남고 실패한 청크의
  DB 변경은 롤백된다. 전체 지역의 원자적 교체를 보장하는 구조는 아니다.
- `IteratorItemReader`는 체크포인트를 저장하지 않으므로 재시작하면 시군구를 처음부터
  다시 읽는다. 이미 적재된 지역도 Upsert로 다시 계산·저장한다.
- 기존 `afterJob` 캐시 삭제는 유지한다. 실패해도 이전 청크가 이미 갱신됐을 수 있어
  주거 점수 캐시를 제거하고 다음 요청에서 다시 계산하도록 한다.
- 프로세스 강제 종료로 메타데이터가 `STARTED` 등에 남으면 실행 중인 것으로 판단한다.
  실제 실행이 종료됐는지 확인한 후 Batch 메타데이터 복구가 필요하다.

## 검증과 범위

외부 API·실제 DB에 접속하지 않는 테스트로 시작 시 판단, 월·연도 경계,
중복 실행, 실패 이력, 비활성화, JSON/XML 응답 및 오류 응답을 확인한다.

```bash
./gradlew test --tests '*DwellingBatchRunnerTest' --tests '*MolitAptRentAdapterTest'
./gradlew bootJar
```

공공 API 응답 형식 근거: [국토교통부 아파트 전월세 실거래가 자료](https://www.data.go.kr/data/15126474/openapi.do).
월초 수집 시점 이후 신고·정정된 자료는 다음 달 재집계 때 반영된다.
기존 수집 범위(지역·월당 첫 페이지, 최대 1,000건)와 통계 산식은 이번 변경에서 유지한다.

## CI/CD

기존 GitHub Actions의 `push` 트리거를 제거하고 `workflow_dispatch`만 남겼다.
GitHub에서 비활성화한 워크플로는 다시 활성화해야 수동 실행할 수 있다.
브랜치 푸시만으로 자동 빌드·배포하지 않는다.
