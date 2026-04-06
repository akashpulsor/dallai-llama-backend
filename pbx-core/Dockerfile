# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build



WORKDIR /app
COPY . /app
RUN mvn -q -DskipTests package -pl pbx-core -am

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
COPY --from=build /app/pbx-core/target/*.jar /app/app.jar
# 👇 THIS IS CRITICAL
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

