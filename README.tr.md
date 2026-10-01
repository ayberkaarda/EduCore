# EduCore

[English](./README.md) | **Türkçe**

EduCore, Spring Boot API ve React (Vite) arayüzü kullanan bir eğitim yönetim sistemidir. Hesapları, dersleri ve kayıtları yönetir; CSV dosyalarını içe aktarır ve yöneticilerin öğrenci hesaplarına IPv4 atama kuralları tanımlamasını sağlar.

## Ekran görüntüleri

| | |
| --- | --- |
| <img src="screenshots/login.png" width="100%" alt="Giriş"><br>Giriş | <img src="screenshots/dashboard.png" width="100%" alt="Panel"><br>Panel |
| <img src="screenshots/students.png" width="100%" alt="Öğrenciler"><br>Öğrenciler | <img src="screenshots/courses.png" width="100%" alt="Dersler"><br>Dersler |
| <img src="screenshots/job-logs.png" width="100%" alt="İş kayıtları"><br>İş kayıtları | |

## API özeti

Sürümlü API dört ana rota grubuna ayrılır:

- `/api/v1/auth` — giriş, oturum yenileme ve kapatma, oturum açmış kullanıcının bilgileri ve parola değiştirme.
- `/api/v1/me` — kullanıcının kendi profili ve ders kayıtları.
- `/api/v1/courses` — oturum açmış kullanıcıların ders kataloğu; `/api/v1/weather` da giriş gerektirir.
- `/api/v1/admin/...` — yalnızca ADMIN rolüne açık hesap/öğrenci listeleri ve yönetimi, dersler, IP kuralları, iş günlükleri, güvenlik olayları ve hesap kayıtları.

Roller `ADMIN` ve `USER` şeklindedir. HTTP yöntemleri ve istek/yanıt biçimleri için [API rota sözleşmesine](docs/api/ROUTES.md), her rotanın erişim kuralları için [RBAC matrisine](docs/security/RBAC_MATRIX.md) bakın.

## Yetkilendirme ve güvenlik

`ADMIN`, yönetim rotalarını kullanabilir. `USER`, kendi profiline ve ders kayıtlarına, ayrıca giriş gerektiren ders kataloğu ve hava durumu uç noktalarına erişebilir. Hesap ve öğrenci listeleri yalnızca ADMIN rolüne açıktır. Anonim erişim, oturum uç noktaları ve belgelenmiş genel URL kurallarıyla sınırlıdır.

Yetkilendirme URL kuralları ve Spring metot güvenliğiyle uygulanır. Tipli kimlik `AuthenticatedUser(id, username, role)` biçimindedir; rol her istekte veritabanından okunur. Kullanıcının kendisine ait işlemler hesap kimliğini bu kimlikten alır; yönetici işlemleri korumalı hesap kimlikleri kullanır. Böylece IDOR erişimleri engellenir. Kullanıcının kendi rolünü düşürmesi veya hesabını silmesi ve etkin son yöneticinin rolünü düşürmek ya da hesabını silmek reddedilir. Hesaplarda iyimser kilitleme, eski veriye dayanan eşzamanlı yazma işlemlerini reddeder.

Yönetici değişiklikleri, değişikliğin kaydedildiği işlemle aynı veritabanı işlemi içinde `security_event` tablosuna yazılır. Olay türleri: `ACCOUNT_CREATED`, `ACCOUNT_UPDATED`, `ACCOUNT_DELETED`, `ROLE_CHANGED`, `ENROLLMENT_CHANGED`, `COURSE_CHANGED`, `IP_RULE_CHANGED` ve `JOB_LOGS_DELETED`. Değişiklik yaratmayan istekler olay oluşturmaz.

Şimdiye kadar tamamlanan güvenlik çalışmaları; sırların ortam yapılandırmasına taşınması, kimlik doğrulamanın güçlendirilmesi, yetkilendirmenin uygulanması ve SOAP servisinin kaldırılmasıdır. Planlanan işler: P4 girdi doğrulaması ve Problem Details; P5 ağ sınırı güvenliği, ağ düzeyinde IP engelleme ve hız sınırlama; P6 veri alım yaşam döngüsü; P7 hesap yaşam döngüsü; P8 arayüz platformu; P9 SEO. Öğrenci IP kuralları şu anda hesaplara IP atanmasını doğrular; ağ trafiğini engellemez. Ayrıntılar için [2026-09-25 temel denetim raporuna](docs/audit/2026-09-25-baseline.md) bakın.

## Kaynak kod düzeni

Arka uç özellik paketlerine ayrılmıştır: `account`, `auth`, `course`, `enrollment`, `ipaccess` ve `ingestion`. Ortak web/API davranışları `common` altında; kimlik doğrulama ve erişim denetimleri `security` altında, denetim hizmetleri ve güvenlik olayları ise `security.audit` altında bulunur. Yapılandırma, veritabanı varlıkları, repository'ler ve DTO'lar kendi paketlerindedir. Veritabanı göç dosyaları `src/main/resources/db/migration` dizinindedir.

## Testler

Arka uçta birim testleri ve PostgreSQL kullanan Testcontainers entegrasyon testleri bulunur. P3 doğrulama raporunda 60 birim testi (bunların 2'si `ApiExceptionHandlerTest`) ve 248 entegrasyon testi, toplam 308 test raporlanmıştır; başarısız, hatalı veya atlanan test yoktur. Tüm doğrulamayı depo kök dizininden çalıştırın. Testcontainers için Docker çalışır durumda olmalıdır:

```bash
./mvnw verify
```

Windows'ta `mvnw.cmd verify` komutunu çalıştırın.

## Hızlı başlangıç

`.env.example` dosyasını `.env` olarak kopyalayıp gerekli ortam değişkenlerini doldurun, ardından Docker Compose ile servisleri başlatın:

```bash
cp .env.example .env
docker compose up --build
```

Arayüz `http://localhost:3000`, API ise `http://localhost:8081` adresindedir. Kurulum ayrıntıları ortam şablonunda ve uygulama profil dosyalarında yer alır.
