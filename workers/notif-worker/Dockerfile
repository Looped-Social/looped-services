# Multi-stage build for notif-worker (SQS -> APNs)

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy Maven project files first for better layer caching
COPY pom.xml ./
COPY workers/notif-worker/pom.xml workers/notif-worker/pom.xml

# Copy sources
COPY . .

# Build only the worker module and its dependencies (uber-jar via shade plugin)
RUN mvn -q -pl workers/notif-worker -am -DskipTests package

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
ENV JAVA_OPTS=""

COPY --from=build /workspace/workers/notif-worker/target/notif-worker-0.0.1-SNAPSHOT.jar /app/app.jar

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]

