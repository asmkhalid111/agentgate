FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl

COPY apps/demo-tools/target/*.jar app.jar

ENV JAVA_OPTS="-Xmx128m -Xms64m -XX:+UseSerialGC"

EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

