#!/usr/bin/env bash
set -euo pipefail

# ====== CONFIG ======
GROUP_ID="com.dalai.llama"
VERSION="0.1.0"
JAVA_VERSION="17"
SPRING_BOOT_VERSION="3.3.2"

MODULES=("auth-service" "pbx-core" "prediction-service")
PORT_auth_service=8080
PORT_pbx_core=8081
PORT_prediction_service=8082

NS_auth_service="auth"
NS_pbx_core="telephony"
NS_prediction_service="predictions"

# ====== HELPERS ======
to_pkg_dir() { echo "$1" | sed 's/\./\//g'; }
mkjava() { mkdir -p "$1/src/main/java/$2"; }
mkres() { mkdir -p "$1/src/main/resources"; }
mktest() { mkdir -p "$1/src/test/java/$2"; }

# ====== ROOT POM ======
cat > pom.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>${GROUP_ID}</groupId>
  <artifactId>dalai-llama-backend</artifactId>
  <version>${VERSION}</version>
  <packaging>pom</packaging>
  <name>dalai-llama-backend</name>
  <description>Multi-module Spring Boot backend (auth, pbx-core, prediction-service)</description>

  <modules>
    <module>auth-service</module>
    <module>pbx-core</module>
    <module>prediction-service</module>
  </modules>

  <properties>
    <java.version>${JAVA_VERSION}</java.version>
    <spring.boot.version>${SPRING_BOOT_VERSION}</spring.boot.version>
    <maven.compiler.source>\${java.version}</maven.compiler.source>
    <maven.compiler.target>\${java.version}</maven.compiler.target>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>\${spring.boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
EOF

# ====== .gitignore ======
cat > .gitignore <<'EOF'
# Maven
target/
!.mvn/wrapper/maven-wrapper.jar
.mvn/wrapper/maven-wrapper.jar
.mvn/wrapper/maven-wrapper.properties
.mvn/wrapper/MavenWrapperDownloader.java

# IDE
.idea/
*.iml
.project
.classpath
.settings/
.vscode/

# OS
.DS_Store

# Node (if added later)
node_modules/
dist/
build/
EOF

# ====== COMMON FILE SNIPPETS ======
BASE_PKG_DIR="$(to_pkg_dir "$GROUP_ID")"

gen_module_pom() {
  local module="$1"
  local artifactId="$module"
  local extraDeps="$2"

  cat > ${module}/pom.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <parent>
    <groupId>${GROUP_ID}</groupId>
    <artifactId>dalai-llama-backend</artifactId>
    <version>${VERSION}</version>
  </parent>
  <modelVersion>4.0.0</modelVersion>
  <artifactId>${artifactId}</artifactId>
  <name>${artifactId}</name>

  <dependencies>
    <!-- Spring Boot Web & Actuator -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    ${extraDeps}

    <!-- Test -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
EOF
}

gen_application() {
  local module="$1"
  local className="$2"

  cat > ${module}/src/main/java/${BASE_PKG_DIR}/${module//-/_}/${className}.java <<EOF
package ${GROUP_ID}.${module//-/_};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ${className} {
    public static void main(String[] args) {
        SpringApplication.run(${className}.class, args);
    }
}
EOF
}

gen_controller() {
  local module="$1"
  local className="$2"

  cat > ${module}/src/main/java/${BASE_PKG_DIR}/${module//-/_}/HelloController.java <<EOF
package ${GROUP_ID}.${module//-/_};

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() {
        return "${module} says hello";
    }
}
EOF
}

gen_application_yml() {
  local module="$1"
  local port="$2"

  cat > ${module}/src/main/resources/application.yml <<EOF
server:
  port: ${port}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

spring:
  application:
    name: ${module}
EOF
}

gen_security_deps_if_auth() {
  cat <<'EOF'
    <!-- Security (auth-service) -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
EOF
}

gen_prediction_deps_if_prediction() {
  cat <<'EOF'
    <!-- WebClient for outbound calls (e.g., to LLM or web search) -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
    <!-- Jackson -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
EOF
}

gen_pbx_deps_if_pbx() {
  cat <<'EOF'
    <!-- SIP stack (JAIN SIP RI) -->
    <dependency>
      <groupId>javax.sip</groupId>
      <artifactId>jain-sip-ri</artifactId>
      <version>1.2.263</version>
    </dependency>
    <!-- (Optional) RTP utilities (jitsi-rtp old artifact as placeholder) -->
    <dependency>
      <groupId>org.jitsi</groupId>
      <artifactId>jitsi-rtp</artifactId>
      <version>1.0-20170505.203841-1</version>
    </dependency>
EOF
}

gen_dockerfile() {
  local module="$1"

  cat > ${module}/Dockerfile <<EOF
# Build stage
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /app
COPY . /app
RUN mvn -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:${JAVA_VERSION}-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
COPY target/${module}-${VERSION}.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java \$JAVA_OPTS -jar /app/app.jar"]
EOF
}

gen_k8s() {
  local module="$1"
  local namespace="$2"
  local port="$3"

  mkdir -p ${module}/k8s

  cat > ${module}/k8s/namespace.yaml <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: ${namespace}
EOF

  cat > ${module}/k8s/deployment.yaml <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${module}
  namespace: ${namespace}
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ${module}
  template:
    metadata:
      labels:
        app: ${module}
    spec:
      containers:
        - name: ${module}
          image: ghcr.io/your-org/${module}:${VERSION}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: ${port}
          env:
            - name: JAVA_OPTS
              value: "-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: ${port}
            initialDelaySeconds: 10
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: ${port}
            initialDelaySeconds: 20
            periodSeconds: 20
EOF

  cat > ${module}/k8s/service.yaml <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${module}
  namespace: ${namespace}
spec:
  selector:
    app: ${module}
  ports:
    - name: http
      port: ${port}
      targetPort: ${port}
      protocol: TCP
  type: ClusterIP
EOF
}

# ====== CREATE MODULES ======
for module in "${MODULES[@]}"; do
  mkdir -p "${module}"
  pkg="${BASE_PKG_DIR}/${module//-/_}"
  mkjava "${module}" "${pkg}"
  mkres "${module}"
  mktest "${module}" "${pkg}"

  # dependencies per module
  extraDeps=""
  case "$module" in
    auth-service)
      extraDeps="$(gen_security_deps_if_auth)"
      ;;
    pbx-core)
      extraDeps="$(gen_pbx_deps_if_pbx)"
      ;;
    prediction-service)
      extraDeps="$(gen_prediction_deps_if_prediction)"
      ;;
  esac

  gen_module_pom "${module}" "${extraDeps}"

  # App + Controller
  AppClass="$(echo "${module^}" | sed 's/-/_/g')App"
  gen_application "${module}" "${AppClass}"
  gen_controller "${module}" "${AppClass}"

  # application.yml with proper port
  case "$module" in
    auth-service) gen_application_yml "${module}" "${PORT_auth_service}" ;;
    pbx-core) gen_application_yml "${module}" "${PORT_pbx_core}" ;;
    prediction-service) gen_application_yml "${module}" "${PORT_prediction_service}" ;;
  esac

  # Docker & K8s
  gen_dockerfile "${module}"
  case "$module" in
    auth-service) gen_k8s "${module}" "${NS_auth_service}" "${PORT_auth_service}" ;;
    pbx-core) gen_k8s "${module}" "${NS_pbx_core}" "${PORT_pbx_core}" ;;
    prediction-service) gen_k8s "${module}" "${NS_prediction_service}" "${PORT_prediction_service}" ;;
  esac
done

# ====== ROOT README (brief) ======
cat > README.md <<'EOF'
# dalai-llama-backend

Multi-module Spring Boot backend:
- auth-service (namespace: auth)
- pbx-core (namespace: telephony)
- prediction-service (namespace: predictions)

## Build
```bash
mvn clean install
