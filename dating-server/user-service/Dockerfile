FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

COPY target/*.jar app.jar
RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 18081 19081

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:18081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "-XX:+UseG1GC", "app.jar"]
