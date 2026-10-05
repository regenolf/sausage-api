# --- Build ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# --- Runtime ---
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 sausage
COPY --from=build /app/target/sausage-api-*.jar app.jar
USER sausage
# Keine Nutzungsstatistiken von Liquibase nach außen senden
ENV LIQUIBASE_ANALYTICS_ENABLED=false
EXPOSE 8080
# Ohne curl/wget im Image: Readiness-Endpunkt per Bash-TCP abfragen
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health/readiness HTTP/1.0\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'
# Heap an den Container-Speicher anpassen (Standard wären nur 25 %)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
