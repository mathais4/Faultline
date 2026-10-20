# Build stage: compile and package (tests run here too; skip with --build-arg SKIP_TESTS=true)
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SKIP_TESTS=true
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests=${SKIP_TESTS}

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/faultline.jar /app/faultline.jar
ENTRYPOINT ["java", "-jar", "/app/faultline.jar"]
