# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Cap Maven's forked-JVM heap -- the default sizing lets compiler/annotation-processing
# plugins balloon memory usage during `package`, which starves the host on constrained dev machines.
ENV MAVEN_OPTS="-Xmx768m"

# Install parent POM only (skip module resolution)
COPY pom.xml /app/
RUN mvn -N install -q

# job-lifecycle-common is a sibling module dependency (not on Maven Central) -- install it into
# the local repo before building pre-production-service against it.
COPY job-lifecycle-common /app/job-lifecycle-common
WORKDIR /app/job-lifecycle-common
RUN mvn -q -DskipTests install

# Copy only pre-production-service
COPY pre-production-service /app/pre-production-service

# Build from pre-production-service directory directly
WORKDIR /app/pre-production-service
RUN mvn -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
COPY --from=build /app/pre-production-service/target/pre-production-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
