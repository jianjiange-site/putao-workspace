# Mobile Gateway Dockerfile
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy fat jar
COPY target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Expose ports (HTTP + gRPC)
EXPOSE 18080 19080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:18080/actuator/health || exit 1

USER appuser

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
