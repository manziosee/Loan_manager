# ── Build stage ───────────────────────────────────────────────────────────────
FROM clojure:temurin-21-tools-deps AS builder
WORKDIR /app

# Cache deps layer separately from source
COPY deps.edn build.clj ./
RUN clojure -P

COPY src       src/
COPY resources resources/
RUN clojure -T:build uber

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install curl for healthcheck
RUN apk add --no-cache curl

# Non-root user
RUN addgroup -S loanmanager && adduser -S loanmanager -G loanmanager

# WORKDIR is created while still root, so the default KYC upload directory
# needs to exist and be writable before switching users — otherwise
# loanmanager.storage.local's mkdirs at runtime fails with permission denied.
RUN mkdir -p /app/data/uploads && chown -R loanmanager:loanmanager /app/data

USER loanmanager

COPY --from=builder --chown=loanmanager:loanmanager /app/target/loanmanager.jar .

# OCI image labels
ARG BUILD_DATE
ARG GIT_SHA
LABEL org.opencontainers.image.title="LoanOS" \
      org.opencontainers.image.description="Bank-grade Loan Management Platform" \
      org.opencontainers.image.vendor="LoanOS" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.source="https://github.com/loanmanager/loanmanager"

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+ExitOnOutOfMemoryError \
               -Dfile.encoding=UTF-8 \
               -Djava.security.egd=file:/dev/./urandom"

ENV LOG_FORMAT=json

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:8080/api/v1/health/live || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar loanmanager.jar"]
