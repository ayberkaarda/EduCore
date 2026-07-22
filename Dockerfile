# 1. Aşama: Maven ile projeyi derleme
FROM maven:3.9.5-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Testleri atlayarak projeyi paketle (Hızlandırmak için)
RUN mvn clean package -DskipTests

# 2. Aşama: Çalıştırma ortamı
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# Derlenen jar dosyasını kopyala
COPY --from=build /app/target/*.jar app.jar

# Uygulamanın çalışacağı port
EXPOSE 8080

# Uygulamayı başlat
ENTRYPOINT ["java", "-jar", "app.jar"]