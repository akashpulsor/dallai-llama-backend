FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
ARG JAR=target/blob-manager-0.3.0.jar
COPY ${JAR} app.jar
ENV JAVA_OPTS=""
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
