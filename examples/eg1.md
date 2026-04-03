```mermaid
subgraph Data Processing Layer [데이터 스트림 처리 계층]
    direction TB
    API_GW -->|인증 및 인가 성공| Kafka
    API_GW -->|인증 실패| ErrorLog((보안 에러 로그))

    Kafka --> StreamProcessor[[Apache Flink 스트림 프로세서]]
    StreamProcessor --> Valid{스키마 유효성 검증 (Schema Registry)}

    Valid -->|정상 페이로드| DB
    Valid -->|파싱 오류 또는 스키마 불일치| DLQ
end

subgraph Analytics Layer [데이터 분석 및 시각화 계층]
    direction LR
    DB --> Dashboard([Grafana 실시간 대시보드])
    DB --> Report[[일일 배치 리포트 생성기]]
end

%% 시각적 테마 및 스타일링 정의
classDef errorState fill:#ffe6e6,stroke:#ff0000,stroke-width:2px,color:#900;
classDef secureState fill:#e6ffe6,stroke:#008000,stroke-width:2px;

class ErrorLog,DLQ errorState;
class DB,Kafka secureState;
```