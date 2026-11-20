# Container image for Azure Container Apps (not built or pushed as part of this assessment).
# Build first: mvn -B verify   (produces target/payment-notification-intake-*.jar)
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/payment-notification-intake-*.jar app.jar

USER app
EXPOSE 8080
# Container-aware heap; no secrets are passed as build args or baked into the image.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
