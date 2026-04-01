#
# HAL1000 Dockerfile
# - Stage 1: Build frontend (Vite) with Node
# - Stage 2: Build backend jar with Gradle (Java 17), copying prebuilt frontend into resources/static
# - Stage 3: Slim runtime with Temurin JRE 17
#

# ---------- Frontend build ----------
FROM node:20-alpine AS frontend
WORKDIR /app/frontend

# Install deps first for better layer caching
COPY frontend/package*.json ./
RUN npm ci --no-fund --no-audit

# Build Vite app
COPY frontend/ .
RUN npm run build

# ---------- Backend build ----------
FROM gradle:8.7-jdk17-alpine AS builder
WORKDIR /home/gradle/src

# Copy entire project (owner preserved)
COPY --chown=gradle:gradle . .

# Copy prebuilt frontend into Spring Boot static resources
RUN mkdir -p src/main/resources/static && rm -rf src/main/resources/static/* && \
    true
COPY --from=frontend /app/frontend/dist/ ./src/main/resources/static/

# Build fat jar; skip Gradle-integrated frontend since we already built it
RUN ./gradlew --no-daemon clean bootJar -x frontendBuild

# Normalize jar name for next stage
RUN cp build/libs/*.jar /home/gradle/src/app.jar

# ---------- Runtime ----------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# App jar
COPY --from=builder /home/gradle/src/app.jar /app/app.jar

# Expose Spring Boot default port
EXPOSE 8080

# Optional: set a writable data dir for SQLite
VOLUME ["/data"]

# Allow JAVA_OPTS overrides (e.g., memory flags)
ENV JAVA_OPTS=""

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]

