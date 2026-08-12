FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY modelrag-common/pom.xml modelrag-common/pom.xml
COPY modelrag-knowledge/pom.xml modelrag-knowledge/pom.xml
COPY modelrag-indexing/pom.xml modelrag-indexing/pom.xml
COPY modelrag-search/pom.xml modelrag-search/pom.xml
COPY modelrag-qa/pom.xml modelrag-qa/pom.xml
COPY modelrag-agent/pom.xml modelrag-agent/pom.xml
COPY modelrag-server/pom.xml modelrag-server/pom.xml
RUN mvn -q -pl modelrag-server -am dependency:go-offline
COPY modelrag-common modelrag-common
COPY modelrag-knowledge modelrag-knowledge
COPY modelrag-indexing modelrag-indexing
COPY modelrag-search modelrag-search
COPY modelrag-qa modelrag-qa
COPY modelrag-agent modelrag-agent
COPY modelrag-server modelrag-server
RUN mvn -q -pl modelrag-server -am -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/modelrag-server/target/modelrag-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
