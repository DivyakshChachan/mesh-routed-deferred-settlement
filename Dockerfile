# ============================================================
# Multi-stage Dockerfile for UPI Offline Mesh
# Stage 1: Build with Maven
# Stage 2: Run with minimal JRE 21
# ============================================================

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Copy Maven wrapper and pom first (layer caching for dependencies)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and build
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create a non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=builder /app/target/*.jar app.jar

# Directory for persistent RSA keys
RUN mkdir -p /var/secrets && chown appuser:appuser /var/secrets

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
