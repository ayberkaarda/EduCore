# EduCore - Educational Management System & Security Framework

Bu proje, arka planda **Spring Boot** (Java, Spring Data JPA, Spring Security) ve ön planda **React** (Vite) kullanılarak geliştirilmiş kapsamlı bir eğitim yönetim sistemidir. Uygulama; **Spring Batch** tabanlı asenkron CSV işleme motoru ve JWT güvenliğinin yanı sıra, sisteme yönelik siber tehditleri engellemek amacıyla ağ düzeyinde güvenlik sağlayan bir **IP Bloklama (IP Management/IpBlock)** modülü içerir.

# 🛠️ Mimari ve Teknolojiler

![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=for-the-badge&logo=spring-security&logoColor=white)
![React](https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white)

### 🌓 Ana Sayfa ve Giriş Sayfası
| Dashboard | Login Page |
|:-------------------------:|:------------------------:|
| <img src="./screenshots/maindash.jpeg" width="100%" alt="dashboard Home"> | <img src="./screenshots/loginPage.jpeg" width="100%" alt="Loginpage"> |

## ✨ Gelişmiş Özellikler

* **JWT Kimlik Doğrulama:** `AuthController` ve `JwtService` ile güçlendirilmiş güvenli oturum açma, kayıt ve rol tabanlı (ADMIN/USER) koruma altyapısı.
* **Asenkron CSV Toplu İşleme:** `csv_uploads/` altındaki veri setlerini arka planda işleyen `BatchConfig` mimarisi. Başarıyla işlenen dosyalar `.done`, hatalı olanlar `.fail` olarak işaretlenir ve `JobLogs.jsx` üzerinden takip edilir.
* **IP Yönetimi & Güvenlik:** Kötü niyetli veya yetkisiz erişim isteklerini engellemek için geliştirilen `IpAddressUtil` ve `IpBlockRepository` katmanı. Sistem yöneticileri `IpManagement.jsx` paneli üzerinden şüpheli IP adreslerini dinamik olarak engelleyebilir.
* **Kapsamlı Yönetim Paneli:** Kurs kataloğu, öğrenci listesi, rol yönetimi, toplu iş logları ve IP yönetimini içeren modüler arayüz.

---
## 🧪 API & Arka Plan Testing
Sistem REST mimarisi `ApiController` ve `AuthController` üzerinden doğrulanmış; JWT filtreleme mekanizmaları (`JwtAuthenticationFilter`) ile ağ güvenlik katmanları entegre edilerek istemci-sunucu arasındaki veri güvenliği tescillenmiştir.

---
## 📂 Dizin Yapısı ve Önemli Konumlar

* **`controller/`**: Güvenlik, veri akışı ve IP engelleme isteklerini yöneten denetleyiciler.
* **`util/` & `repository/`**: IP doğrulama mantığını barındıran `IpAddressUtil` ve veritabanı şemaları (`IpBlockRepository`, `JobLogRepository`).
* **`config/`**: Güvenlik yapılandırmaları (`SecurityConfig`), toplu iş yönetimi (`BatchConfig`) ve asenkron iş takipçileri (`JobTracker`).
* **`csv_uploads/`**: Sisteme yüklenen toplu veri setlerinin tutulduğu dizin.
* **`frontend/src/`**: `IpManagement.jsx`, `JobLogs.jsx` ve modern React bileşenlerinin yer aldığı arayüz dizini.

---

## 🛠️ Kurulum & Çalıştırma (Local Development)

### 1. Gereksinimler
* Java Development Kit (JDK)
* Node.js & npm
* Maven
* Veritabanı Konfigürasyonu (`application.properties` bağlantı bilgileri)

Projeyi klonlayın ve kök dizine gidin:
```bash
git clone <repo-url>
```
```bash
# 2. Maven kullanarak projeyi çalıştırın:
./mvnw spring-boot:run
````
```bash
# 3. Ön Yüzü (Frontend) Çalıştırma:
Ayrı bir terminal penceresinde frontend dizinine geçiş yapın, bağımlılıkları yükleyin ve geliştirici sunucusunu ayağa kaldırın
cd frontend
npm install
npm run dev
````

👨‍💻 Geliştirici İletişim
Ayberk Arda

Software Developer | Computer Programming, Istanbul Kültür University (İKÜ)
