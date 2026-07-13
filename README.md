# EduCore - Educational Management System

Bu proje, arka planda **Spring Boot** (Java, Spring Data JPA, Spring Security) ve ön planda **React** (Vite) kullanılarak geliştirilmiş, güvenli ve yüksek performanslı bir eğitim yönetim sistemidir. Uygulama, **JWT (JSON Web Token)** tabanlı kimlik doğrulama mimarisine ek olarak, sistem yöneticilerinin büyük veri setlerini asenkron olarak sisteme işlemesine olanak tanıyan bir **Spring Batch / CSV İşleme** mekanizması içerir.

# 🛠️ Mimari ve Teknolojiler

![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=for-the-badge&logo=spring-security&logoColor=white)
![React](https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white)

### 🌓 Ana Sayfa ve Giriş Sayfası
| Dashboard | Login Page) |
|:-------------------------:|:------------------------:|
| <img src="./screenshots/maindash.jpeg" width="100%" alt="dashboard Home"> | <img src="./screenshots/loginPage.jpeg" width="100%" alt="Loginpage"> |

## ✨ CRUD & Güvenlik Özellikleri

Uygulama; Kullanıcı (Account), Kurs (Course) ve Kayıt (Enrollment) mekanizmaları üzerinde şu kabiliyetleri sunar:

* **JWT Kimlik Doğrulama:** `AuthController` ve `JwtService` ile güçlendirilmiş güvenli oturum açma, kayıt ve rol tabanlı (ADMIN/USER) koruma altyapısı.
* **Asenkron CSV Toplu İşleme (Batch):** `csv_uploads/` dizini altındaki kurs listeleri ve öğrenci verileri gibi CSV dosyalarını otomatik olarak işleyen `BatchConfig` ve `CsvJobService` mekanizması. Başarıyla işlenen dosyalar `.done`, hatalı olanlar `.fail` olarak etiketlenir.
* **İş Takip Günlükleri (Job Logs):** Toplu veri yükleme işlemlerinin durumunu, başarı oranlarını ve hata detaylarını kaydeden `JobTracker` ve `JobLog` entegrasyonu.
* **Kapsamlı Yönetim Paneli:** Kurs kataloğu, öğrenci listesi, rol yönetimi ve toplu iş loglarının izlenebildiği (`JobLogs.jsx`) modüler React arayüzü.

---
## 🧪 API & Frontend Testing
Sistem REST mimarisi `ApiController` ve `AuthController` üzerinden test edilmiş; JWT filtreleme mekanizmaları (`JwtAuthenticationFilter`) ve `GlobalExceptionHandler` sınıfları ile hata/güvenlik süreçleri uçtan uca doğrulanmıştır. Ön yüz asenkron çağrıları ve geciktirme (`useDebounce`) mekanizmalarıyla optimize edilmiştir.
---
## 📂 Dizin Yapısı ve Önemli Konumlar

* **`controller/`**: Güvenlik ve veri akışını yöneten `AuthController` ve `ApiController` sınıfları.
* **`config/`**: Güvenlik yapılandırmaları (`SecurityConfig`), toplu iş yönetimi (`BatchConfig`) ve asenkron iş takipçilerini (`JobTracker`) içerir.
* **`security/`**: JWT yetkilendirmesini ve CORS konfigürasyonlarını içeren `SecurityConfig` ve servis katmanları.
* **`service/` & `controller/`**: CSV dosyalarını işleyen `CsvJobService` ile istemci taleplerini yöneten API uç noktaları.
* **`csv_uploads/`**: Sisteme yüklenen ve işleme durumlarına göre (`.done`/`.fail`) ayrıştırılan CSV veri setlerinin tutulduğu dizin.
* **`entity/` & `dto/`**: Veritabanı şemaları (`Account`, `Course`, `Enrollment`) ve veri transfer nesneleri (DTO).
* **`frontend/src/`**: Login ekranı, kurs yönetim paneli ve öğrenci profillerini barındıran modern React arayüz bileşenleri.
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
