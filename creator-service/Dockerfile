# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY . /app

RUN mvn -q -DskipTests package -pl creator-service -am

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

ENV JAVA_OPTS="\
-XX:MaxRAMPercentage=70.0 \
-XX:MaxMetaspaceSize=512m \
-XX:+UseG1GC \
-XX:+UseStringDeduplication \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp \
"

COPY --from=build /app/creator-service/target/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
