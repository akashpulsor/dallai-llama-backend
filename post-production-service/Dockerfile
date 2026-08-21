# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Install parent POM only (skip module resolution)
COPY pom.xml /app/
RUN mvn -N install -q

# Copy only post-production-service
COPY post-production-service /app/post-production-service

# Build from post-production-service directory directly
WORKDIR /app/post-production-service
RUN mvn -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
COPY --from=build /app/post-production-service/target/post-production-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
