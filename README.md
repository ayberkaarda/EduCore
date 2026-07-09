# EduCore - Educational Management System

Bu proje, arka planda **Spring Boot** (Java, Spring Data JPA) ve ön planda **React** kullanılarak geliştirilmiş, kurs yönetimi ve öğrenci kayıt süreçlerini otomatize eden tam yığın (Full-Stack) bir eğitim yönetim sistemidir. Proje; kullanıcı, kurs ve kayıt verileri üzerinde temel CRUD operasyonlarını destekler.

# 🛠️ Mimari ve Teknolojiler

![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![React](https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white)

## ✨ CRUD Özellikleri

Uygulama; Hesap (Account), Kurs (Course) ve Kayıt (Enrollment) verileri üzerinde aşağıdaki işlemleri entegre şekilde destekler:

* **GET:** Kurs listesini, öğrenci detaylarını ve kullanıcı profillerini listeleme.
* **POST:** Yeni kullanıcı hesabı oluşturma, kurs ekleme veya bir kursa kayıt (Enrollment) gerçekleştirme.
* **PUT:** Kullanıcı bilgilerini ve kurs detaylarını güncelleme.
* **DELETE:** Sistemden bir kurs kaydını veya kullanıcıyı silme.

---
## 🧪 API & Frontend Testing
Uygulamanın tüm REST endpoint'leri `ApiController` mimarisi üzerinden doğrulanmıştır. React tarafındaki form doğrulama süreçleri, arama geciktirme mekanizmaları (`useDebounce`) ve asenkron API çağrıları entegre edilerek istemci-sunucu arasındaki veri bütünlüğü titizlikle kontrol edilmiştir.

---
## 📂 Dizin Yapısı ve Önemli Konumlar

* **`src/main/java/.../controller/`**: İstemciden gelen istekleri karşılayan `ApiController` sınıfı.

* **`src/main/java/.../entity/`**: Veritabanı tablolarına karşılık gelen veri modelleri (`Account`, `Course`, `Enrollment`, `Role`).

* **`src/main/java/.../repository/`**: Veritabanı etkileşimi için Spring Data arayüzleri.

* **`frontend/src/`**: Kurs ve kullanıcı yönetimi arayüz bileşenlerini içeren React dizini.

* **`src/main/resources/`**: Uygulama konfigürasyonları (`application.properties`).
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
