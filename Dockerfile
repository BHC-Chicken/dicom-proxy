# Java 21 실행 환경을 제공하는 가벼운 Alpine 베이스 이미지 사용
FROM eclipse-temurin:21-jre-alpine

# 컨테이너 내부에 작업 디렉토리 생성
WORKDIR /app

# [추가] 레거시 TLS(1.0, 1.1)를 차단하는 보안 정책을 해제합니다.
# Alpine 환경에서 java.security 파일의 일반적인 경로를 수정합니다.
RUN sed -i 's/TLSv1, TLSv1.1, //g' $JAVA_HOME/conf/security/java.security

# 방금 Gradle로 빌드한 JAR 파일을 컨테이너 안의 app.jar로 복사
COPY build/libs/*.jar app.jar

# [수정] 클라이언트 프로토콜 설정까지 포함하여 환경변수 지정
ENV JAVA_OPTS="-Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2 \
               -Djdk.tls.client.protocols=TLSv1,TLSv1.1,TLSv1.2 \
               -Dsun.security.ssl.allowUnsafeRenegotiation=true"

# 스프링 부트 기본 포트 노출
EXPOSE 8080

# 컨테이너가 켜질 때 스프링 부트 앱 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]