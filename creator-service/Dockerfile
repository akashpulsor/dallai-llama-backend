# RNNoise stage
FROM ubuntu:22.04 AS rnnoise-build

ARG RNNOISE_REF=master

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        git \
        autoconf \
        automake \
        libtool \
        make \
        gcc \
        g++ \
        pkg-config \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /rnnoise
RUN git clone --depth 1 --branch "${RNNOISE_REF}" https://github.com/xiph/rnnoise.git . \
    && ./autogen.sh \
    && ./configure --prefix=/usr/local \
    && make -j"$(nproc)" \
    && make install \
    && install -m 0755 examples/.libs/rnnoise_demo /usr/local/bin/rnnoise_demo

# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY . /app

RUN mvn -q -DskipTests package -pl creator-service -am

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

ARG DEEPFILTER_VERSION=0.5.6
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        ca-certificates \
        curl \
        fontconfig \
        fonts-dejavu-core \
        fonts-liberation \
        fonts-noto-core \
    && curl -fsSL "https://github.com/Rikorose/DeepFilterNet/releases/download/v${DEEPFILTER_VERSION}/deep-filter-${DEEPFILTER_VERSION}-x86_64-unknown-linux-musl" -o /usr/local/bin/deep-filter \
    && chmod +x /usr/local/bin/deep-filter \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

COPY --from=rnnoise-build /usr/local/bin/rnnoise_demo /usr/local/bin/rnnoise_demo
COPY --from=rnnoise-build /usr/local/lib/librnnoise.so* /usr/local/lib/
ENV LD_LIBRARY_PATH=/usr/local/lib
RUN ldconfig

WORKDIR /app

ENV JAVA_OPTS="\
-XX:MaxRAMPercentage=45.0 \
-XX:MaxMetaspaceSize=512m \
-XX:+UseG1GC \
-XX:+UseStringDeduplication \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp \
"

COPY --from=build /app/creator-service/target/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
