FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl

COPY apps/gateway/target/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=dev
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseSerialGC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

