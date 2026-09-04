# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Install parent POM only (skip module resolution)
COPY pom.xml /app/
RUN mvn -N install -q

# Copy only video-generation-service
COPY video-generation-service /app/video-generation-service

# Build from video-generation-service directory directly
WORKDIR /app/video-generation-service
RUN mvn -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
# ffmpeg is invoked by FinalRenderService via ProcessBuilder to concatenate per-shot MP4s
# into one project-level deliverable -- pure CPU work, no GPU. ~40MB of image size; the
# apt lists are removed in the same layer to keep the final size down.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
COPY --from=build /app/video-generation-service/target/video-generation-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
