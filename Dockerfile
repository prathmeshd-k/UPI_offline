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

# Set Spring profile to docker (uses application-docker.properties)
ENV SPRING_PROFILES_ACTIVE=docker

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the application with docker profile
ENTRYPOINT ["java", "-Dspring.profiles.active=docker", "-jar", "app.jar"]
