# Mesma imagem para os perfis api, worker e all: o perfil e toda a configuração vêm de variáveis de ambiente (CLAUDE.md, seção 8).
FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/clinic-backend-*.jar extract --layers --destination /build/layers

FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin clinic
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/layers/application/ ./
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/clinic-backend-0.0.1-SNAPSHOT.jar"]
