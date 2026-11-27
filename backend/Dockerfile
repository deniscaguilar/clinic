# Mesma imagem para os perfis api, worker e all: o perfil e toda a configuração vêm de variáveis de ambiente (CLAUDE.md, seção 8).
FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/clinic-backend-*.jar extract --layers --destination /build/layers

FROM eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin clinic
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/layers/application/ ./
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/clinic-backend-0.0.1-SNAPSHOT.jar"]
