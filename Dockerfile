# 1. Aşama: Maven ve Java 21 ile projeyi derleme
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Testleri atlayarak projeyi paketle
RUN mvn clean package -DskipTests

# 2. Aşama: Java 21 Çalıştırma ortamı
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Derlenen jar dosyasını kopyala
COPY --from=build /app/target/*.jar app.jar

# Uygulamanın çalışacağı port
EXPOSE 8080

# Uygulamayı başlat
ENTRYPOINT ["java", "-jar", "app.jar"]