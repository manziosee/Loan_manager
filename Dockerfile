# ── Build stage ───────────────────────────────────────────────────────────────
FROM clojure:temurin-21-tools-deps AS builder
WORKDIR /app

# Cache deps separately from source
COPY deps.edn .
RUN clojure -P

COPY src       src
COPY resources resources
COPY build.clj .
RUN clojure -T:build uber

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S loanmanager && adduser -S loanmanager -G loanmanager
USER loanmanager

COPY --from=builder --chown=loanmanager:loanmanager /app/target/loanmanager.jar .

EXPOSE 8080

# JVM tuning: container-aware heap, GC logging off by default
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -Dfile.encoding=UTF-8 \
               -Djava.security.egd=file:/dev/./urandom"

ENV LOG_FORMAT=json

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/v1/health/live || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar loanmanager.jar"]
