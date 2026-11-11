# syntax=docker/dockerfile:1

# ---- build stage: compile, package and split the fat jar into layers ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Resolve dependencies first so they stay cached until the pom changes.
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests -Djacoco.skip=true package \
    && cp target/ingest-gateway-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- runtime stage: JRE only, non-root, layered for small image diffs ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /application
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -q -O /dev/null http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "application.jar"]
