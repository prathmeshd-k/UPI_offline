# Multi-stage build for optimized production image
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy Maven files
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn/

# Download dependencies
RUN ./mvnw dependency:resolve

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw clean package -DskipTests

# Production stage - runtime image
FROM eclipse-temurin:17-jre-jammy

# Create app user (security best practice)
RUN useradd -m -u 1000 appuser

WORKDIR /app

# Copy JAR from builder stage
COPY --from=builder /app/target/upi-offline-mesh-*.jar app.jar

# Change ownership
RUN chown appuser:appuser /app

# Switch to non-root user
USER appuser

# Expose port (default 8080, but can be overridden)
EXPOSE 8080

# Optional: Set Spring profile. Defaults to default (H2 db). Set to 'docker' to use postgres.
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-default}

# Health check (uses PORT env var which is standard in cloud providers)
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:${PORT:-8080}/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
