import type { Locale } from './site-map.mjs'

// All text of the public site in Turkish and English. Every statement here must be true of the code in this
// repository (README "Features", docs/api/ROUTES.md, docs/ops/DATA_RETENTION.md, docs/integrations/WEBHOOKS.md).
// No institution, customer, figure or testimonial is invented: course data comes from the public API at build
// time, and the operating institution is referred to generically. Each fact is stated once per page and the
// product is always named "EduCore" (docs/brand/BRAND_IDENTITY.md section 2).

export { SOURCE_REPOSITORY_URL, VULNERABILITY_REPORT_URL } from './project-links.mjs'

export interface Section {
  /** Rendered as an H2, phrased as the question people ask. */
  heading: string
  paragraphs?: string[]
  items?: string[]
}

export interface PageCopy {
  /** Text of the <title> before " · EduCore" (the landing page uses `title` verbatim). */
  title: string
  /** Meta description, 140-160 characters (checked by tests/public-copy.test.ts). */
  description: string
  h1: string
  /** Answer-first paragraph: what EduCore is, for whom, what it does (<= 60 words on landing, about and FAQ). */
  lead: string
  sections: Section[]
}

export interface FaqEntry {
  question: string
  answer: string
}

export interface Copy {
  languageName: string
  ui: {
    skipToContent: string
    primaryNav: string
    languageSwitcher: string
    nav: { home: string; courses: string; about: string; faq: string; privacy: string; security: string }
    signIn: string
    footerLine: string
    footerNav: string
    breadcrumb: string
    facts: {
      heading: string
      name: string
      type: string
      typeValue: string
      audience: string
      audienceValue: string
      functions: string
      functionsValue: string
      languages: string
      languagesValue: string
      publishedCourses: string
      source: string
      dateModified: string
      notPublished: string
    }
  }
  home: PageCopy & { catalogHeading: string; catalogIntro: (count: number) => string; catalogLink: string; recentHeading: string }
  courses: {
    title: string
    description: (count: number) => string
    h1: string
    lead: (count: number) => string
    searchLabel: string
    searchHint: string
    noMatch: string
    term: string
    instructor: string
    updated: string
    listLabel: string
  }
  course: {
    descriptionFallback: string
    noDescription: string
    term: string
    instructor: string
    updated: string
    notSet: string
    enrolHeading: string
    enrolText: string
    backToCatalog: string
    metaSuffix: string
  }
  about: PageCopy
  faq: PageCopy & { entries: FaqEntry[] }
  privacy: PageCopy
  security: PageCopy & { reportLinkLabel: string }
  notFound: { title: string; description: string; h1: string; lead: string; homeLink: string; catalogLink: string }
}

const tr: Copy = {
  languageName: 'Türkçe',
  ui: {
    skipToContent: 'İçeriğe geç',
    primaryNav: 'Ana gezinme',
    languageSwitcher: 'Dil seçimi',
    nav: { home: 'Ana sayfa', courses: 'Dersler', about: 'Hakkında', faq: 'SSS', privacy: 'Gizlilik', security: 'Güvenlik' },
    signIn: 'Oturum aç',
    footerLine: 'EduCore · Ders ve öğrenci yönetim platformu',
    footerNav: 'Alt gezinme',
    breadcrumb: 'Konum',
    facts: {
      heading: 'Künye',
      name: 'Ad',
      type: 'Tür',
      typeValue: 'Web tabanlı ders ve öğrenci yönetim platformu',
      audience: 'Kimler için',
      audienceValue: 'Eğitim kurumlarının yöneticileri, personeli ve öğrencileri',
      functions: 'Başlıca işlevler',
      functionsValue: 'Öğrenci kayıtları, ders kataloğu, derse kayıt, CSV içe aktarımı, rol tabanlı erişim',
      languages: 'Site dilleri',
      languagesValue: 'Türkçe, İngilizce',
      publishedCourses: 'Yayımlanan ders sayısı',
      source: 'Kaynak kod',
      dateModified: 'Katalog son güncelleme',
      notPublished: 'Henüz yayımlanmadı',
    },
  },
  home: {
    title: 'EduCore — Ders ve Öğrenci Yönetim Platformu',
    description: 'EduCore, eğitim kurumları için ders ve öğrenci yönetim platformudur: öğrenci kayıtları, ders kataloğu, derse kayıt, CSV içe aktarımı ve rol tabanlı erişim.',
    h1: 'EduCore: ders ve öğrenci yönetim platformu',
    lead: 'EduCore, eğitim kurumları için bir ders ve öğrenci yönetim platformudur. Yöneticiler öğrenci ve ders kayıtlarını tutar, kayıtları CSV dosyalarından toplu olarak içe aktarır ve erişimi rollere göre sınırlar. Öğrenciler dönem derslerini görür ve derslere kendileri kaydolur. Bu site, EduCore kurulumunda yayımlanan dersleri listeler.',
    sections: [
      {
        heading: 'EduCore ile neler yapılır?',
        items: [
          'Öğrenci kaydı oluşturma, düzenleme ve silme; yeni öğrenciye tek kullanımlık geçici parola verilir.',
          'Ders kataloğunu yönetme ve öğrencileri derslere kaydetme.',
          'Öğrenci ve ders listelerini CSV dosyalarından içe aktarma; her içe aktarım satır düzeyinde sonuçlarla iş kaydına yazılır.',
          'Öğrencilere atanabilecek IPv4 adreslerini tek adres, aralık veya alt ağ kuralı olarak tanımlama.',
          'İçe aktarım, ders ve hesap olaylarını HMAC imzalı webhook çağrılarıyla başka sistemlere bildirme.',
        ],
      },
      {
        heading: 'EduCore\'u kimler kullanır?',
        paragraphs: [
          'Hesapların iki rolü vardır: Yönetici ve Kullanıcı. Yöneticiler öğrenci, ders, kullanıcı, içe aktarım ve erişim kurallarını yönetir. Kullanıcılar ders kataloğunu ve kendi profillerini görür; öğrenciler dönem derslerini profillerinden seçer.',
        ],
      },
      {
        heading: 'Kayıtlar nasıl korunur?',
        paragraphs: [
          'Yetki her istekte sunucuda denetlenir, oturumlar kısa ömürlü erişim belirteçleriyle yürür ve yönetici işlemleri denetim kaydına yazılır. Ayrıntılar güvenlik ve gizlilik sayfalarındadır.',
        ],
      },
    ],
    catalogHeading: 'Hangi dersler yayımlandı?',
    catalogIntro: count => `EduCore kataloğunda şu anda ${count} ders yayımlanmış durumda. Son güncellenen dersler:`,
    catalogLink: 'Tüm dersleri görün',
    recentHeading: 'Son güncellenen dersler',
  },
  courses: {
    title: 'Ders kataloğu',
    description: count => `EduCore ders kataloğu: yayımlanan ${count} dersin adı, dönemi, öğretim görevlisi ve açıklaması. Ders sayfalarında derse nasıl kaydolunacağı da anlatılır.`,
    h1: 'Ders kataloğu',
    lead: count => `Bu katalog, EduCore'da yayımlanan ${count} dersi ada göre sıralı olarak listeler. Her ders için dönem, öğretim görevlisi ve açıklama gösterilir; derse kayıt, EduCore'da oturum açarak yapılır.`,
    searchLabel: 'Derslerde ara',
    searchHint: 'Ders adı, dönem veya öğretim görevlisi yazın.',
    noMatch: 'Aramanızla eşleşen ders yok.',
    term: 'Dönem',
    instructor: 'Öğretim görevlisi',
    updated: 'Güncellendi',
    listLabel: 'Yayımlanan dersler',
  },
  course: {
    descriptionFallback: 'EduCore kataloğunda yayımlanan ders.',
    noDescription: 'Bu ders için açıklama yayımlanmadı.',
    term: 'Dönem',
    instructor: 'Öğretim görevlisi',
    updated: 'Son güncelleme',
    notSet: 'Belirtilmedi',
    enrolHeading: 'Bu derse nasıl kaydolunur?',
    enrolText: 'Öğrenci hesabınızla EduCore\'da oturum açın, Profilim sayfasını açın ve dönem dersleri arasından bu dersi seçin. Hesabınız yoksa kurumunuzun yönetimine başvurun; hesapları yöneticiler oluşturur.',
    backToCatalog: 'Ders kataloğuna dön',
    metaSuffix: 'EduCore ders kataloğundaki bu sayfa dönem, öğretim görevlisi ve derse kayıt adımlarını gösterir.',
  },
  about: {
    title: 'Hakkında',
    description: 'EduCore nedir, kimin için yapılmıştır ve nasıl çalışır: öğrenci, ders ve kayıt verilerini tek veritabanında tutan rol tabanlı web uygulaması hakkında bilgiler.',
    h1: 'EduCore hakkında',
    lead: 'EduCore, bir eğitim kurumunun öğrenci, ders ve derse kayıt verilerini tek bir PostgreSQL veritabanında tutan web tabanlı bir yönetim platformudur. Yöneticiler, personel ve öğrenciler için tasarlanmıştır; kayıtları rol tabanlı bir web arayüzü ve bir REST API üzerinden yönetir.',
    sections: [
      {
        heading: 'EduCore nasıl çalışır?',
        paragraphs: [
          'Sunucu tarafı Java 21 ve Spring Boot ile yazılmış bir REST API\'dir; arayüz React ile yazılmış bir web uygulamasıdır. Bu herkese açık sayfalar derleme sırasında statik HTML olarak üretilir ve JavaScript olmadan okunabilir.',
          'CSV içe aktarımı iki yoldan başlar: dosya izlenen bir klasöre bırakılır ya da bir yönetici dosyayı İçe aktarım ekranından yükler. Dosya doğrulanır, Spring Batch işiyle işlenir ve sonuç satır düzeyinde iş kaydına yazılır.',
        ],
      },
      {
        heading: 'EduCore neleri yapmaz?',
        items: [
          'Bir öğrenme yönetim sistemi değildir: ders içeriği, ödev, sınav veya not tutmaz.',
          'IP kuralları yalnızca öğrencilere atanan adreslerin geçerliliğini denetler; ağ trafiğini engellemez veya süzmez.',
          'Herkese açık kayıt formu yoktur; hesapları yöneticiler tek tek ya da CSV ile oluşturur.',
        ],
      },
      {
        heading: 'EduCore\'un kaynak kodu nerede?',
        paragraphs: [
          'Kaynak kod, belgeler ve değişiklik geçmişi GitHub üzerindeki ayberkaarda/EduCore deposundadır. Kurulum, yapılandırma ve güvenlik belgeleri depodaki docs klasöründe yer alır.',
        ],
      },
    ],
  },
  faq: {
    title: 'Sıkça sorulan sorular',
    description: 'EduCore hakkında sık sorulanlar: kimler oturum açabilir, derse nasıl kaydolunur, CSV içe aktarımı nasıl çalışır, hangi roller vardır ve veriler nasıl korunur.',
    h1: 'Sıkça sorulan sorular',
    lead: 'EduCore, eğitim kurumları için bir ders ve öğrenci yönetim platformudur: yöneticiler kayıtları tutar ve CSV ile içe aktarır, öğrenciler derslere kendileri kaydolur. Aşağıdaki yanıtlar oturum açma, derse kayıt, içe aktarım, roller ve verilerin korunması hakkındaki soruları kısaca karşılar.',
    sections: [],
    entries: [
      {
        question: 'EduCore\'da kimler oturum açabilir?',
        answer: 'Yalnızca bir yöneticinin oluşturduğu hesaplar. Yöneticiler öğrencileri tek tek ya da CSV içe aktarımıyla ekler; yeni öğrenci tek kullanımlık geçici bir parola alır ve ilk oturumda parolasını değiştirir.',
      },
      {
        question: 'Bir derse nasıl kaydolunur?',
        answer: 'Öğrenci EduCore\'da oturum açar, Profilim sayfasında dönem derslerini görür ve istediği dersi seçer. Yöneticiler de herhangi bir hesabı bir derse kaydedebilir.',
      },
      {
        question: 'CSV içe aktarımı nasıl çalışır?',
        answer: 'Dosya izlenen klasöre bırakılır ya da bir yönetici tarafından yüklenir. Başlık, kodlama ve boyut denetlenir; geçerli satırlar Spring Batch işiyle yazılır, yinelenen veya hatalı satırlar nedenleriyle birlikte iş kaydında listelenir.',
      },
      {
        question: 'Hangi roller vardır?',
        answer: 'İki rol vardır: Yönetici ve Kullanıcı. Yönetici öğrenci, ders, kullanıcı, içe aktarım, IP kuralı ve webhook ekranlarını kullanır; Kullanıcı ders kataloğunu ve kendi profilini görür.',
      },
      {
        question: 'Oturumlar ne kadar sürer?',
        answer: 'Erişim belirteci 15 dakika geçerlidir ve HttpOnly bir çerezdeki 14 günlük yenileme belirteciyle yenilenir. Çalınmış bir yenileme belirtecinin yeniden kullanılması tüm oturum ailesini iptal eder; art arda hatalı girişler hesabı geçici olarak kilitler.',
      },
      {
        question: 'Ders kataloğunda hangi bilgiler herkese açıktır?',
        answer: 'Yalnızca yayımlanmış derslerin adı, dönemi, öğretim görevlisi, açıklaması ve son güncelleme zamanı. Öğrenci, hesap ve derse kayıt bilgileri herkese açık sayfalarda hiçbir zaman yer almaz.',
      },
      {
        question: 'Herkese açık sayfalar çerez veya izleme kullanır mı?',
        answer: 'Hayır. Bu sayfalar çerez yazmaz, analiz veya reklam betiği yüklemez ve yazı tiplerini kendi sunucusundan sunar. Çerez yalnızca oturum açtığınızda, yenileme belirteci için kullanılır.',
      },
      {
        question: 'Verilerimin kopyasını alabilir veya hesabımı sildirebilir miyim?',
        answer: 'Evet. Hesap sahibi verilerinin JSON kopyasını indirebilir ve silme isteyebilir; silme 30 günlük bir bekleme süresinden sonra kalıcı olur ve bu süre içinde geri alınabilir. Ayrıntılar gizlilik sayfasındadır.',
      },
    ],
  },
  privacy: {
    title: 'Gizlilik',
    description: 'EduCore sayfalarının ve uygulamanın hangi kişisel verileri ne kadar süre tuttuğu, KVKK ve GDPR kapsamındaki haklarınız ve bu hakları nasıl kullanacağınız.',
    h1: 'Gizlilik',
    lead: 'Bu sayfa, EduCore\'un herkese açık sayfalarının ve oturum açılan uygulamanın hangi kişisel verileri işlediğini, ne kadar süre sakladığını ve KVKK ile GDPR kapsamındaki haklarınızı nasıl kullanabileceğinizi açıklar.',
    sections: [
      {
        heading: 'Herkese açık sayfalar hangi verileri toplar?',
        paragraphs: [
          'Çerez yazmaz, analiz veya reklam betiği yüklemez, üçüncü taraf kaynak çağırmaz. Web sunucusu her istek için standart bir erişim günlüğü satırı (IP adresi, zaman, istenen adres, tarayıcı bilgisi) yazar; bu satırlar kapsayıcı günlükleri döndürülene kadar tutulur.',
        ],
      },
      {
        heading: 'Uygulama hesap sahipleri hakkında neleri saklar?',
        items: [
          'Hesap: kullanıcı adı, ad, soyad, öğrenci numarası, atanmış IP adresi, rol ve parolanın bcrypt özeti (parolanın kendisi saklanmaz).',
          'Derse kayıtlar: hangi derse ne zaman kaydolunduğu.',
          'Oturum açma denemeleri: kullanıcı adının anahtarlı özeti, istemci IP adresi ve sonuç; 90 gün saklanır.',
          'Denetim kaydı: oturum ve yönetici işlemleri, istemci IP adresleriyle birlikte; 365 gün saklanır.',
        ],
      },
      {
        heading: 'Hangi haklarınız var ve nasıl kullanılır?',
        items: [
          'Erişim ve taşınabilirlik: hesap sahibi profilini, derse kayıtlarını ve kendi güvenlik olaylarını tek bir JSON dosyası olarak indirebilir.',
          'Düzeltme: ad ve soyad profilden değiştirilir; öğrenci numarası ve IP adresi için kurumun yönetimine başvurulur.',
          'Silme: hesap sahibi güncel parolasıyla silme ister; hesap 30 gün sonra kalıcı olarak silinir ve bu süre içinde geri alınabilir. Denetim kayıtlarında kimlik, takma adla değiştirilir.',
          'İşlemenin kısıtlanması: bir yönetici hesabı devre dışı bırakabilir; veriler saklanır ancak hesap kullanılamaz.',
        ],
      },
      {
        heading: 'Veri sorumlusu kimdir?',
        paragraphs: [
          'Bu EduCore kurulumunu işleten eğitim kurumu veri sorumlusudur. Haklarınızla ilgili başvurularınızı kurumun yönetimine iletin. Webhook ile dış sistemlere gönderilen olaylar yalnızca kimlik numaraları, durum değerleri ve sayılar içerir; ad, öğrenci numarası veya kullanıcı adı içermez.',
        ],
      },
    ],
  },
  security: {
    title: 'Güvenlik ve sorumlu bildirim',
    description: 'EduCore\'da bir güvenlik açığı nasıl bildirilir, bildirimde neler olmalı, hangi testler kapsam dışında kalır ve platform hangi güvenlik önlemlerini kullanır.',
    h1: 'Güvenlik ve sorumlu bildirim',
    lead: 'EduCore\'da bir güvenlik açığı bulduysanız lütfen herkese açık bir kanalda paylaşmadan önce özel olarak bildirin. Bu sayfa bildirim yolunu, bildirimde beklenen bilgileri ve test sırasında uyulması gereken kuralları açıklar.',
    reportLinkLabel: 'Güvenlik açığını özel olarak bildirin',
    sections: [
      {
        heading: 'Bir güvenlik açığını nasıl bildiririm?',
        paragraphs: [
          'EduCore yazılımındaki açıkları, kaynak kod deposunun güvenlik bildirimi formuyla (GitHub özel güvenlik açığı bildirimi) özel olarak iletin. Herkese açık bir konu (issue) açmayın. Yalnızca belirli bir kurulumu ilgilendiren yapılandırma sorunlarını o kurulumu işleten kuruma bildirin.',
        ],
      },
      {
        heading: 'Bildirimde neler olmalı?',
        items: [
          'Etkilenen adres, API yolu veya bileşen ve kullanılan sürüm ya da tarih.',
          'Sorunu yeniden üretmek için adımlar ve gözlenen ile beklenen davranış.',
          'Olası etki: hangi verilere veya işlemlere erişilebildiği.',
        ],
      },
      {
        heading: 'Test ederken nelere uymalıyım?',
        items: [
          'Yalnızca kendi hesaplarınızı ve kendi kurduğunuz test ortamlarını kullanın.',
          'Başkalarına ait verilere erişmeyin, verileri değiştirmeyin veya silmeyin; erişirseniz testi durdurun ve bildirin.',
          'Hizmet dışı bırakma, yoğun otomatik tarama ve sosyal mühendislik kapsam dışıdır.',
        ],
      },
      {
        heading: 'EduCore hangi güvenlik önlemlerini kullanır?',
        items: [
          'Parolalar bcrypt (maliyet 12) ile özetlenir; erişim belirteçleri 15 dakika geçerlidir, yenileme belirteçleri her kullanımda değişir.',
          'Oturum açma IP başına sınırlanır ve art arda hatalı denemeler hesabı kilitler.',
          'Rol yetkileri sunucuda hem adres kurallarıyla hem de yöntem düzeyinde denetlenir.',
          'Herkese açık sayfalar satır içi betik içermez ve katı bir içerik güvenlik politikasıyla sunulur.',
          'Webhook çağrıları HMAC-SHA256 ile imzalanır ve özel ağ adreslerine gönderilmez.',
        ],
      },
    ],
  },
  notFound: {
    title: 'Sayfa bulunamadı',
    description: 'İstediğiniz adres EduCore\'un herkese açık sayfalarından hiçbirine karşılık gelmiyor. Ana sayfadan veya ders kataloğundan aradığınız içeriğe ulaşabilirsiniz.',
    h1: 'Sayfa bulunamadı',
    lead: 'Bu adres EduCore\'un herkese açık sayfalarından hiçbirine karşılık gelmiyor. Ders yayından kaldırılmış ya da adres yanlış yazılmış olabilir.',
    homeLink: 'Ana sayfaya git',
    catalogLink: 'Ders kataloğunu açın',
  },
}

const en: Copy = {
  languageName: 'English',
  ui: {
    skipToContent: 'Skip to content',
    primaryNav: 'Main navigation',
    languageSwitcher: 'Language',
    nav: { home: 'Home', courses: 'Courses', about: 'About', faq: 'FAQ', privacy: 'Privacy', security: 'Security' },
    signIn: 'Sign in',
    footerLine: 'EduCore · Course and student management platform',
    footerNav: 'Footer navigation',
    breadcrumb: 'Breadcrumb',
    facts: {
      heading: 'Facts',
      name: 'Name',
      type: 'Type',
      typeValue: 'Web-based course and student management platform',
      audience: 'For',
      audienceValue: 'Administrators, staff and students of educational institutions',
      functions: 'Main functions',
      functionsValue: 'Student records, course catalog, enrollment, CSV import, role-based access',
      languages: 'Site languages',
      languagesValue: 'Turkish, English',
      publishedCourses: 'Published courses',
      source: 'Source code',
      dateModified: 'Catalog last updated',
      notPublished: 'Not published yet',
    },
  },
  home: {
    title: 'EduCore — Course and Student Management Platform',
    description: 'EduCore is a course and student management platform for educational institutions: student records, course catalog, enrollment, CSV import and role-based access.',
    h1: 'EduCore: course and student management platform',
    lead: 'EduCore is a course and student management platform for educational institutions. Administrators keep student and course records, import them in bulk from CSV files and restrict access by role. Students see their term courses and enrol themselves. This site lists the courses published in this EduCore installation.',
    sections: [
      {
        heading: 'What can you do with EduCore?',
        items: [
          'Create, edit and delete student records; a new student receives a one-time temporary password.',
          'Manage the course catalog and enrol students in courses.',
          'Import student and course lists from CSV files; every import is written to a job log with row-level results.',
          'Define the IPv4 addresses that may be assigned to students as a single address, a range or a subnet rule.',
          'Notify other systems of import, course and account events through HMAC-signed webhook calls.',
        ],
      },
      {
        heading: 'Who uses EduCore?',
        paragraphs: [
          'Accounts have one of two roles: Administrator or User. Administrators manage students, courses, users, imports and access rules. Users see the course catalog and their own profile; students select their term courses from their profile.',
        ],
      },
      {
        heading: 'How are the records protected?',
        paragraphs: [
          'Permissions are checked on the server for every request, sessions run on short-lived access tokens and administrator actions are written to an audit trail. The security and privacy pages give the details.',
        ],
      },
    ],
    catalogHeading: 'Which courses are published?',
    catalogIntro: count => `The EduCore catalog currently lists ${count} published ${count === 1 ? 'course' : 'courses'}. Most recently updated:`,
    catalogLink: 'See all courses',
    recentHeading: 'Recently updated courses',
  },
  courses: {
    title: 'Course catalog',
    description: count => `The EduCore course catalog: name, term, instructor and description of ${count} published ${count === 1 ? 'course' : 'courses'}. Each course page also explains how to enrol in it.`,
    h1: 'Course catalog',
    lead: count => `This catalog lists the ${count} ${count === 1 ? 'course' : 'courses'} published in EduCore, sorted by name. Each entry shows the term, the instructor and the description; enrolment happens after signing in to EduCore.`,
    searchLabel: 'Search courses',
    searchHint: 'Type a course name, term or instructor.',
    noMatch: 'No courses match your search.',
    term: 'Term',
    instructor: 'Instructor',
    updated: 'Updated',
    listLabel: 'Published courses',
  },
  course: {
    descriptionFallback: 'A course published in the EduCore catalog.',
    noDescription: 'No description has been published for this course.',
    term: 'Term',
    instructor: 'Instructor',
    updated: 'Last updated',
    notSet: 'Not specified',
    enrolHeading: 'How do I enrol in this course?',
    enrolText: 'Sign in to EduCore with your student account, open My profile and select this course among the term courses. If you have no account, contact the administration of your institution; accounts are created by administrators.',
    backToCatalog: 'Back to the course catalog',
    metaSuffix: 'This EduCore catalog page shows the term, the instructor and the steps to enrol in the course.',
  },
  about: {
    title: 'About',
    description: 'What EduCore is, who it is for and how it works: a role-based web application that keeps student, course and enrollment records in one database, and its limits.',
    h1: 'About EduCore',
    lead: 'EduCore is a web-based management platform that keeps an educational institution\'s student, course and enrollment records in one PostgreSQL database. It is built for administrators, staff and students, and manages the records through a role-based web interface and a REST API.',
    sections: [
      {
        heading: 'How does EduCore work?',
        paragraphs: [
          'The server side is a REST API written in Java 21 with Spring Boot; the interface is a web application written in React. These public pages are generated as static HTML at build time and can be read without JavaScript.',
          'A CSV import starts in one of two ways: a file is dropped into a watched folder, or an administrator uploads it on the Import screen. The file is validated, processed by a Spring Batch job and the result is written to a job log row by row.',
        ],
      },
      {
        heading: 'What does EduCore not do?',
        items: [
          'It is not a learning management system: it keeps no lesson content, assignments, exams or grades.',
          'IP rules only check that addresses assigned to students are valid; they do not block or filter network traffic.',
          'There is no public sign-up form; administrators create accounts one by one or by CSV import.',
        ],
      },
      {
        heading: 'Where is the source code of EduCore?',
        paragraphs: [
          'The source code, documentation and change history are in the ayberkaarda/EduCore repository on GitHub. Installation, configuration and security documents are in the docs folder of the repository.',
        ],
      },
    ],
  },
  faq: {
    title: 'Frequently asked questions',
    description: 'Common questions about EduCore: who can sign in, how to enrol in a course, how the CSV import works, which roles exist and how the records are protected.',
    h1: 'Frequently asked questions',
    lead: 'EduCore is a course and student management platform for educational institutions: administrators keep the records and import them from CSV files, and students enrol in courses themselves. The answers below cover signing in, enrolment, imports, roles and how the data is protected.',
    sections: [],
    entries: [
      {
        question: 'Who can sign in to EduCore?',
        answer: 'Only accounts created by an administrator. Administrators add students one by one or through a CSV import; a new student receives a one-time temporary password and changes it at the first sign-in.',
      },
      {
        question: 'How do I enrol in a course?',
        answer: 'A student signs in to EduCore, sees the term courses on the My profile page and selects the course. Administrators can also enrol any account in a course.',
      },
      {
        question: 'How does the CSV import work?',
        answer: 'A file is dropped into the watched folder or uploaded by an administrator. Its header, encoding and size are checked; valid rows are written by a Spring Batch job, and duplicate or invalid rows are listed with their reasons in the job log.',
      },
      {
        question: 'Which roles exist?',
        answer: 'There are two roles: Administrator and User. An Administrator uses the student, course, user, import, IP rule and webhook screens; a User sees the course catalog and their own profile.',
      },
      {
        question: 'How long does a session last?',
        answer: 'The access token is valid for 15 minutes and is renewed with a 14-day refresh token kept in an HttpOnly cookie. Reusing a stolen refresh token revokes the whole session family, and repeated failed sign-ins lock the account for a while.',
      },
      {
        question: 'Which course information is public?',
        answer: 'Only the name, term, instructor, description and last update time of published courses. Student, account and enrollment data never appears on the public pages.',
      },
      {
        question: 'Do the public pages use cookies or tracking?',
        answer: 'No. These pages set no cookies, load no analytics or advertising scripts and serve their fonts from the same server. A cookie is used only after you sign in, for the refresh token.',
      },
      {
        question: 'Can I get a copy of my data or have my account deleted?',
        answer: 'Yes. An account holder can download a JSON copy of their data and request deletion; the deletion becomes permanent after a 30-day grace period and can be undone within it. The privacy page has the details.',
      },
    ],
  },
  privacy: {
    title: 'Privacy',
    description: 'Which personal data the EduCore public pages and the application process, how long it is kept, your rights under KVKK and the GDPR and how to exercise them.',
    h1: 'Privacy',
    lead: 'This page explains which personal data the EduCore public pages and the signed-in application process, how long the data is kept and how you can exercise your rights under KVKK and the GDPR.',
    sections: [
      {
        heading: 'What data do the public pages collect?',
        paragraphs: [
          'They set no cookies, load no analytics or advertising scripts and call no third-party resources. The web server writes a standard access log line for each request (IP address, time, requested address, browser information); these lines are kept until the container logs are rotated.',
        ],
      },
      {
        heading: 'What does the application store about account holders?',
        items: [
          'Account: username, first name, last name, student number, assigned IP address, role and the bcrypt hash of the password (the password itself is never stored).',
          'Enrollments: which course was joined and when.',
          'Sign-in attempts: a keyed hash of the username, the client IP address and the result; kept for 90 days.',
          'Audit trail: sign-in and administrator actions with client IP addresses; kept for 365 days.',
        ],
      },
      {
        heading: 'Which rights do you have and how do you use them?',
        items: [
          'Access and portability: an account holder can download their profile, enrollments and own security events as one JSON file.',
          'Rectification: first and last name are changed on the profile; for the student number and IP address, contact the administration of the institution.',
          'Erasure: the account holder requests deletion with the current password; the account is deleted permanently after 30 days and can be restored within that period. In the audit trail the identity is replaced by a pseudonym.',
          'Restriction of processing: an administrator can deactivate an account; the data is kept but the account cannot be used.',
        ],
      },
      {
        heading: 'Who is the data controller?',
        paragraphs: [
          'The educational institution that operates this EduCore installation is the data controller. Send requests about your rights to the administration of the institution. Events sent to other systems by webhook contain only ids, status values and counts, never names, student numbers or usernames.',
        ],
      },
    ],
  },
  security: {
    title: 'Security and responsible disclosure',
    description: 'How to report a security vulnerability in EduCore, what a report should contain, which tests are out of scope, and the main security measures of the platform.',
    h1: 'Security and responsible disclosure',
    lead: 'If you have found a security vulnerability in EduCore, please report it privately before sharing it anywhere public. This page explains how to report it, what the report should contain and the rules to follow while testing.',
    reportLinkLabel: 'Report a vulnerability privately',
    sections: [
      {
        heading: 'How do I report a vulnerability?',
        paragraphs: [
          'Report vulnerabilities in the EduCore software privately through the security advisory form of the source repository (GitHub private vulnerability reporting). Do not open a public issue. Report configuration problems that concern only one installation to the institution that operates it.',
        ],
      },
      {
        heading: 'What should a report contain?',
        items: [
          'The affected address, API route or component, and the version or date you tested.',
          'Steps to reproduce the problem, and the observed and expected behaviour.',
          'The possible impact: which data or actions became reachable.',
        ],
      },
      {
        heading: 'Which rules apply while testing?',
        items: [
          'Use only your own accounts and test installations you run yourself.',
          'Do not access, change or delete other people\'s data; if you reach it, stop and report.',
          'Denial of service, high-volume automated scanning and social engineering are out of scope.',
        ],
      },
      {
        heading: 'Which security measures does EduCore use?',
        items: [
          'Passwords are hashed with bcrypt (cost 12); access tokens are valid for 15 minutes and refresh tokens change on every use.',
          'Sign-in is rate limited per IP address and repeated failures lock the account.',
          'Role permissions are checked on the server by URL rules and at method level.',
          'The public pages contain no inline scripts and are served with a strict Content Security Policy.',
          'Webhook calls are signed with HMAC-SHA256 and are never sent to private network addresses.',
        ],
      },
    ],
  },
  notFound: {
    title: 'Page not found',
    description: 'The address you requested does not match any public EduCore page. The home page and the course catalog lead to the published content and courses of this site.',
    h1: 'Page not found',
    lead: 'This address does not match any public EduCore page. The course may have been unpublished, or the address may contain a typo.',
    homeLink: 'Go to the home page',
    catalogLink: 'Open the course catalog',
  },
}

export const COPY: Record<Locale, Copy> = { tr, en }

export function copyFor(locale: Locale): Copy {
  return COPY[locale]
}
