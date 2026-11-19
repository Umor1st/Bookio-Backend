FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew installDist --no-daemon -x test
COPY src ./src
RUN ./gradlew installDist --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S bookio && adduser -S -G bookio bookio
WORKDIR /app
COPY --from=build --chown=bookio:bookio /workspace/build/install/ /app/
RUN mkdir -p /app/covers && chown bookio:bookio /app/covers
USER bookio
EXPOSE 8080
CMD ["/app/Bookio-Backend/bin/Bookio-Backend"]
