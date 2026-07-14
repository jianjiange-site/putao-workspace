FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy fat jar
COPY target/*.jar app.jar

# Change ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose ports
EXPOSE 18080 19090

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:18080/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "-XX:+UseG1GC", "app.jar"]
