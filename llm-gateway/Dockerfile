# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Install parent POM only (skip module resolution)
COPY pom.xml /app/
RUN mvn -N install -q

# observability-common is a sibling module dependency (not on Maven Central) -- install it into
# the local repo before building llm-gateway against it. Same pattern pre-production-service's
# Dockerfile uses for job-lifecycle-common.
COPY observability-common /app/observability-common
WORKDIR /app/observability-common
RUN mvn -q -DskipTests install

# Copy only llm-gateway
WORKDIR /app
COPY llm-gateway /app/llm-gateway

# Build from llm-gateway directory directly
WORKDIR /app/llm-gateway
RUN mvn -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
COPY --from=build /app/llm-gateway/target/llm-gateway-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
