FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY modelrag-api/pom.xml modelrag-api/pom.xml
COPY modelrag-common/pom.xml modelrag-common/pom.xml
COPY modelrag-knowledge/pom.xml modelrag-knowledge/pom.xml
COPY modelrag-indexing/pom.xml modelrag-indexing/pom.xml
COPY modelrag-search/pom.xml modelrag-search/pom.xml
COPY modelrag-qa/pom.xml modelrag-qa/pom.xml
COPY modelrag-agent/pom.xml modelrag-agent/pom.xml
COPY modelrag-server/pom.xml modelrag-server/pom.xml
RUN mvn -q -s .mvn/maven-central-settings.xml -pl modelrag-server -am dependency:go-offline
COPY modelrag-api modelrag-api
COPY modelrag-common modelrag-common
COPY modelrag-knowledge modelrag-knowledge
COPY modelrag-indexing modelrag-indexing
COPY modelrag-search modelrag-search
COPY modelrag-qa modelrag-qa
COPY modelrag-agent modelrag-agent
COPY modelrag-server modelrag-server
RUN mvn -q -s .mvn/maven-central-settings.xml -pl modelrag-server -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system modelrag \
    && useradd --system --gid modelrag --home-dir /app --shell /usr/sbin/nologin modelrag
WORKDIR /app
COPY --from=build /workspace/modelrag-server/target/modelrag-server-*.jar /app/app.jar
RUN chown -R modelrag:modelrag /app
USER modelrag
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
