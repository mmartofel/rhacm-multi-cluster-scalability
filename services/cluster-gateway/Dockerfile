FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src/ src/
RUN mvn package -DskipTests -q

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.18
WORKDIR /deployments
COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar .
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/
EXPOSE 8080
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0"
CMD ["java", "-jar", "quarkus-run.jar"]
