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
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
