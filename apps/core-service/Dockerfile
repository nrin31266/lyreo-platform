# Multi-stage image. Build context is repo root because Core depends on Maven sibling modules.
FROM eclipse-temurin:25-jdk-noble AS build
RUN apt-get update && apt-get install -y --no-install-recommends wget unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /repo
COPY . .
RUN ./mvnw -B -pl apps/core-service -am -DskipTests -DskipITs package

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /repo/apps/core-service/target/lyreo-core-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
