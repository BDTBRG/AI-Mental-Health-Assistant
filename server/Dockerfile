FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /workspace
COPY server/pom.xml ./server/pom.xml
COPY server/src ./server/src

# The checked-out application.yml may contain local credentials. It is excluded
# from the Docker context; package only the safe, environment-driven example.
RUN cp server/src/main/resources/application-example.yml \
       server/src/main/resources/application.yml \
    && mvn -B -f server/pom.xml -DskipTests package

FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app
RUN addgroup -S app \
    && adduser -S -G app app

COPY --from=build --chown=app:app /workspace/server/target/amha-server-1.0.0.jar /app/app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
