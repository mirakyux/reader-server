# Runtime-only API image; build the JAR with ./gradlew bootJar first.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache ca-certificates tini tzdata
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY build/backend/libs/reader.jar /app/bin/reader.jar
EXPOSE 8080
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["java", "-jar", "/app/bin/reader.jar"]
