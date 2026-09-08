# Multi-stage image. Build context is repo root because Core depends on Maven sibling modules.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /repo
COPY . .
RUN ./mvnw -B -pl apps/core-service -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /repo/apps/core-service/target/lyreo-core-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
