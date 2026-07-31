# 🏥 DICOM Proxy Service (`dicom-proxy`)

> **Java 21+ Virtual Threads**와 **dcm4che3** 기반의 **DICOM STOW-RS bounded-memory 중계 프록시 서비스**입니다.
> 요청 본문을 애플리케이션 소유 임시 파일에 저장해 검증한 뒤, 전체 본문을 힙에 적재하지 않고 타겟 서버로 스트리밍합니다.

---

## 💡 프로젝트 소개

PACS/EMR 연동 환경에서 DICOM STOW-RS 표준 규격 전송을 수행할 때, 타겟 서버의 요청 URL 경로에는 대표 `{StudyInstanceUID}`가 반드시 명시되어야 합니다:

$$\text{POST } \mathtt{/target-pacs-service/dcm/studies/}\mathbf{\{StudyInstanceUID\}}\mathtt{?SourceID=...}$$

`dicom-proxy`는 수신 payload의 모든 DICOM part를 검사해 단일 Study인지 확인하고 타겟 URL을 자동 완성한 뒤, 검증된 원본 파일을 바이트 변경 없이 스트리밍합니다.

---

## 🚀 핵심 아키텍처 및 기능

### 1. ⚡ Bounded-memory 파일 기반 스트리밍
- `Spring RestClient` + `JdkClientHttpRequestFactory` 기반으로 구현되어, 검증된 spool 파일을 `byte[]`로 만들지 않고 타겟 서버로 스트리밍합니다.
- 직접 STOW-RS 요청과 form 업로드 모두 동일한 검증 경로를 사용하며, spool 크기와 DICOM 메타데이터 스캔 크기에 상한을 적용합니다.

### 2. 🔍 전체 Part 단일 Study 검증
- 모든 multipart part의 `StudyInstanceUID`를 검사합니다. 동일 Study의 여러 인스턴스는 허용하고, 서로 다른 Study가 포함된 payload는 타겟 전송 전에 `400 Bad Request`로 거부합니다.

### 3. 🔄 원본 바이트 보존
- 검증 과정에서 DICOM을 재직렬화하지 않으며, 검증이 끝난 spool 파일 전체를 그대로 전송합니다.

### 4. 🛡️ 가상 스레드 & 동시성 가버너 (Semaphore Governor)
- **Virtual Threads**를 활용하여 다중 파일 요청을 스레드 생성 비용 없이 병렬 처리합니다.
- `Semaphore` 기반 동시성 가버너를 적용하여 타겟 서버 접속과 애플리케이션 spool 수를 각각 제한합니다.
- `multipart/form-data`는 servlet multipart resolver보다 앞선 필터에서도 같은 동시성 상한을 적용해 컨트롤러 진입 전 임시 디스크 사용을 제한합니다.

---

## 📊 APM 성능 벤치마크 (Kibana APM 실측)

8개 DICOM `.dat` 파일(총 120MB+, 1,000+ 인스턴스) 동시 업로드 전송 테스트 시의 Kibana APM 실측 지표 비교입니다.

| 측정 지표 (APM Metrics) | 기존 | **기존 Header Peek 측정값** | 개선 효과 |
| :--- | :---: | :---: | :---: |
| **실제 사용 힙 메모리 (Avg. Used)** | **`1.0 GB` (1,000 MB)** | **`0.24 GB` (240 MB)** | **76% 절감 📉** |
| **OS 확보 힙 메모리 (Avg. Committed)** | **`1.5 GB` (1,500 MB)** | **`0.45 GB` (450 MB)** | **70% 절감 🛡️** |
| **G1 Old Gen 메모리 점유량** | **`720.82 MB` (11.92%)** | **`82.96 MB` (1.35%)** | **88.5% 급감 ⚡** |
| **순간 메모리 할당 속도 (Allocation Rate)** | **`650 MB/sec` (폭발적 양산)** | **`19 MB/sec` (1.14 GB/min)** | **97% 급감 🚀** |
| **8개 파일 Header Peek 전체 소요 시간** | 35.9 초 | **최대 2.2 초 (동시 병렬)** | **16배 속도 향상 ⏱️** |

> 위 수치는 이전 Header Peek 구현의 기록이며, 현재 파일 spool 기반 구현의 성능 수치가 아닙니다. 현재 구현은 운영 payload로 별도 재측정이 필요합니다.

---

## 🏗️ 시스템 아키텍처 다이어그램

```mermaid
sequenceDiagram
    autonumber
    actor Client as 클라이언트 (EMR / Swagger)
    participant Controller as DicomController
    participant Parser as DicomMultipartParserUtil
    participant Spool as Temporary Spool File
    participant Service as DicomWebService
    participant RestClient as RestClient (JdkClientHttpRequestFactory)
    participant Target as 타겟 PACS (target-pacs-service)

    Client->>Controller: POST /api/dicom/forward-async (.dat 첨부파일 수신)
    Controller->>Service: processDatFilesProxy() (VirtualThread 할당)
    
    rect rgb(240, 248, 255)
        note over Service, Parser: 1단계: File spool 및 전체 Part 검증
        Service->>Service: 크기 제한을 적용해 임시 파일 생성
        Service->>Parser: validateSingleStudy(spoolFile)
        Parser->>Parser: Boundary 감지 및 모든 Part의 StudyInstanceUID 검사
        Parser-->>Service: 대표 UID + Boundary + Part 수 반환
    end

    rect rgb(255, 250, 240)
        note over Service, RestClient: 2단계: Bounded-memory 파일 스트리밍
        Service->>Service: 동시성 가버너 (Semaphore Permit 획득)
        Service->>RestClient: POST /target-pacs-service/dcm/studies/{studyUid}?SourceID=...
        note over RestClient: Interceptor 미사용 -> byte[] 버퍼링 없음!
        RestClient->>Target: spoolFile InputStream::transferTo
        Target-->>RestClient: 200 OK (SOPInstanceUID 응답 XML)
        RestClient-->>Service: 전송 성공 결과 반환
        Service->>Service: Semaphore Permit 반납
    end

    Service-->>Controller: DicomForwardResponse 리스트 반환
    Controller-->>Client: 200 OK JSON 응답
```

---

## 🛠️ 기술 스택 (Tech Stack)

- **Language**: Java 21+ (Java 25 추천, Virtual Threads 활성화)
- **Framework**: Spring Boot 4.0.3, Spring Web / WebMVC
- **DICOM Toolkit**: `dcm4che3` (dcm4che-core 5.29.1, dcm4che-mime)
- **HTTP Client**: Spring `RestClient` + `JdkClientHttpRequestFactory` (JDK HttpClient)
- **Monitoring & Observability**: Elastic APM Java Agent, Logstash Logback ECS Encoder
- **Build Tool**: Gradle 8.x / 9.x

---

## 📖 API 사용 가이드

애플리케이션 구동 후 웹 브라우저에서 Swagger UI (`http://localhost:8080/swagger-ui.html`)에 접속하면 API를 직접 테스트해 볼 수 있습니다.

### 다중 `.dat` DICOM 파일 병렬 중계 API

```http
POST /api/dicom/forward-async?SourceID=1.2.410.100110.10.xxxxxx
Content-Type: multipart/form-data

files: [study_1.2.410.xxxxxx.dat, study_1.2.410.yyyyyy.dat]
```

#### 응답 예시 (200 OK):
```json
[
  {
    "studyUid": "1.2.410.200055.900.2462313746.xxxxxx",
    "seriesUid": "1.2.410.200055.17416560743500.xxxxxx",
    "sopInstanceUid": "1.2.410.100110.10.36100137.xxxxxx",
    "results": [
      "[StudyUID: 1.2.410.200055.900.2462313746.xxxxxx] 성공 -> SOPInstanceUID: 1.2.410.100110.10.36100137.xxxxxx"
    ]
  }
]
```
응답 예시의 경우 타겟서버마다 다를 수 있습니다.

---

## ⚙️ 실행 및 배포 환경 설정

### 1. Prerequisites
- Java 25 LTS
- Gradle 8.x / 9.x

### 2. 🔧 실제 구동 시 필수 환경변수 변경 가이드

실제 운영/운영 환경에 배포 시 환경변수(`Environment Variable`) 또는 `application.yaml`에서 반드시 설정해야 하는 핵심 설정값 목록입니다.

| 환경변수 명 (`Environment Variable`) | 기본값 / 예시 설정 | 필수 여부 | 설명 |
| :--- | :--- | :---: | :--- |
| **`BASE_URL`** | `https://pacs.hospital.com:9443` | **🚨 필수** | 중계 목적지 타겟 PACS 서버의 STOW-RS Base URL |
| **`POSTGRESQL_DATASOURCE_URL`** | `jdbc:postgresql://localhost:5432/dicom_db` | **🚨 필수** | PostgreSQL 데이터베이스 접속 URL |
| **`POSTGRESQL_DATASOURCE_USERNAME`** | `postgres` | **🚨 필수** | PostgreSQL 접속 계정 ID |
| **`POSTGRESQL_DATASOURCE_PASSWORD`** | `your_postgres_password` | **🚨 필수** | PostgreSQL 접속 계정 비밀번호 |
| **`DICOM_FORWARD_SPOOL_DIR`** | `${java.io.tmpdir}/dicom-proxy-forward` | **💡 선택** | 검증 전 STOW-RS payload를 저장할 임시 디렉터리 |
| **`DICOM_FORWARD_MAX_SPOOL_SIZE`** | `5GB` | **💡 선택** | payload 한 건의 최대 spool 크기 |
| **`DICOM_FORWARD_MAX_METADATA_SCAN_SIZE`** | `8MB` | **💡 선택** | DICOM part별 UID 메타데이터 최대 스캔 크기 |
| **`DICOM_FORWARD_MAX_STOW_RESPONSE_SIZE`** | `1MB` | **💡 선택** | 타겟 STOW-RS XML 응답의 최대 메모리 처리 크기 |
| **`DICOM_FORWARD_MAX_CONCURRENT_SPOOLS`** | `2` | **💡 선택** | 동시에 생성·검증·전송할 spool 파일 수 |
| **`DICOM_FORWARD_STALE_SPOOL_RETENTION`** | `24h` | **💡 선택** | 비정상 종료 후 남은 전용 spool 파일의 보존 시간 |
| **`PURGE_ENABLED`** | `false` | **💡 선택 (기본값: false)** | DICOM Purge 배치 서비스 활성화 여부 (`true` 설정 시 기능 활성화) |
| **`PURGE_MAX_THREADS`** | `5` | **💡 선택** | DICOM 퍼지(Purge) 아카이빙 배치 처리 시 최대 동시 작업 스레드 수 |
| **`CERTIFICATE_PATH`** | `/app/certs/server.pem` | **💡 선택 (mTLS)** | mTLS 인증 전송 시 사용할 클라이언트 SSL/TLS 인증서 파일 경로 |
| **`CERTIFICATE_PASSWORD`** | `your_cert_password` | **💡 선택 (mTLS)** | 클라이언트 인증서 개인키(Private Key) 비밀번호 |
| **`CAPATH`** | `/app/certs/ca.crt` | **💡 선택 (mTLS)** | 타겟 PACS 서버의 SSL 검증용 CA 루트 인증서 경로 |
| **`DISCORD_WEBHOOK_URL`** | `https://discord.com/api/webhooks/...` | **💡 선택** | 중계 실패 및 모니터링 알림 수신용 디스코드 웹훅 URL (미설정 시 무시) |
| **`SPRING_ELASTICSEARCH_URIS`** | `https://localhost:9200` | **💡 선택 (APM)** | APM 및 트레이싱 로그 수집용 ElasticSearch 엔드포인트 |
| **`SPRING_ELASTICSEARCH_USERNAME`** | `elastic` | **💡 선택 (APM)** | ElasticSearch 계정 ID |
| **`SPRING_ELASTICSEARCH_PASSWORD`** | `your_elastic_password` | **💡 선택 (APM)** | ElasticSearch 계정 비밀번호 |

> Purge 아카이브의 원자적 no-replace publish는 임시 ZIP과 최종 ZIP 사이의 hard link를 사용합니다. Purge 요청의 출력 디렉터리는 hard link를 지원하는 파일시스템이어야 하며, 미지원 파일시스템에서는 기존 ZIP을 덮어쓰지 않고 안전하게 실패합니다.

### 3. `application.yaml` 구성 예시

```yaml
server:
  port: 8080

spring:
  servlet:
    multipart:
      max-file-size: 2GB
      max-request-size: 5GB

base-url: ${BASE_URL:https://target-pacs-server.example.com:9443}

dicom:
  forward:
    spool-dir: ${DICOM_FORWARD_SPOOL_DIR:${java.io.tmpdir}/dicom-proxy-forward}
    max-spool-size: ${DICOM_FORWARD_MAX_SPOOL_SIZE:5GB}
    max-metadata-scan-size: ${DICOM_FORWARD_MAX_METADATA_SCAN_SIZE:8MB}
    max-stow-response-size: ${DICOM_FORWARD_MAX_STOW_RESPONSE_SIZE:1MB}
    max-concurrent-spools: ${DICOM_FORWARD_MAX_CONCURRENT_SPOOLS:2}
```

### 4. 빌드 및 실행 명령

```bash
# 환경변수 적용 후 애플리케이션 실행
BASE_URL="https://pacs.hospital.com:9443" ./gradlew bootRun

# Docker 컨테이너 구동 시
docker-compose up -d
```

---

## 🐍 헬퍼 스크립트: DICOM 자동 그룹핑 & `.dat` 패키징

단일 디렉터리에 섞여 있는 DICOM 파일들을 `StudyInstanceUID` 기준으로 자동 분류하여 STOW-RS `.dat` 멀티파트 규격 파일로 패키징해 주는 파이썬 자동화 스크립트를 함께 제공합니다.

```bash
# pydicom 자동 설치 및 패키징 실행
python3 organize_and_pack_dat.py /path/to/dicom_folder /path/to/output_dir
```

---

## 📄 License
Copyright © 2026 dev.ioexception. All rights reserved.
