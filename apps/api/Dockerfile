# Multi-stage build for Looped API

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy Maven project files first for better layer caching
COPY pom.xml ./
COPY apps/api/pom.xml apps/api/pom.xml
COPY workers/notif-worker/pom.xml workers/notif-worker/pom.xml

# Copy sources
COPY . .

# Build only the API module and its dependencies
RUN mvn -q -pl apps/api -am -DskipTests package

	FROM eclipse-temurin:25-jre-jammy

	# ECS task/container health checks use wget; ensure it's present in the runtime image.
	RUN apt-get update \
	  && apt-get install -y --no-install-recommends wget ca-certificates \
	  && rm -rf /var/lib/apt/lists/*
	ENV JAVA_OPTS="" \
	    PORT=8080
	WORKDIR /app
	EXPOSE 8080

# Healthcheck targets Spring Boot health endpoint
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s CMD sh -c "wget -qO- http://127.0.0.1:${PORT}/health || exit 1"

COPY --from=build /workspace/apps/api/target/looped-api-0.0.1-SNAPSHOT.jar /app/app.jar

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
