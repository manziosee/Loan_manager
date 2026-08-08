# ── Build stage ───────────────────────────────────────────────────────────────
FROM clojure:temurin-21-tools-deps AS builder
WORKDIR /app
COPY deps.edn .
RUN clojure -P
COPY src src
COPY resources resources
RUN clojure -T:build uber

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/loanmanager.jar .
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "loanmanager.jar"]
