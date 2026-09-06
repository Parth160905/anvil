FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/anvil-web.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
