# Android uygulaması “E-Book Reader” (SomeCatCode) için Gizlilik Politikası

Son güncelleme: 12 Ekim 2026

## 1. Veri sorumlusu

Felix Kurth
E-posta: it@wasmitleder.de

## 2. Özet

Uygulama, e-kitapları **cihazınızda seçtiğiniz klasörlerden** ve isteğe bağlı olarak **kendiniz girdiğiniz bir Nextcloud sunucusundan** okur. Geliştirici uygulama için herhangi bir sunucu işletmez ve sizden veya cihazınızdan **hiçbir veri** almaz. Uygulama **reklam, izleme, analiz ve çökme raporlama içermez**.

## 3. Uygulamanın işlediği veriler

Çalışabilmek için uygulama cihazınızda şunları işler:

- **Kimlik bilgileri:** Nextcloud sunucunuzun adresi, kullanıcı adınız ve bir uygulama parolası. Uygulama parolasını, tarayıcı üzerinden oturum açtığınızda (Nextcloud Login Flow) sunucunuz oluşturur. Android Keystore ile şifrelenerek saklanır. Uygulama gerçek Nextcloud parolanızı asla görmez.
- **Kütüphane verileri:** kitaplarınızın başlıkları, yazarları, serileri, türleri, etiketleri, kapakları ve diğer bilgileri ile raflarınız.
- Çevrimdışı okuma için indirdiğiniz **kitap dosyaları**; uygulamanın özel depolama alanında saklanır.
- **Yerel klasörler:** bir klasörü kütüphane olarak eklediğinizde uygulama içindeki kitap dosyalarını okur (Android klasör seçicisi üzerinden, yalnızca o klasör). Böyle bir kütüphanenin başlıkları, kapakları ve diğer bilgileri, okuma konumu ve vurguları yalnızca cihazda kalır. Dosya, okuma konumu ve vurgular, ancak bir kitabı açıkça bir Nextcloud hesabına kopyalar veya taşırsanız o sunucuya gönderilir.
- **Okuma verileri:** okuma konumu, okuma durumu, puanlar, vurgular ve notlar ile okuma konumuyla birlikte gönderilen, seçtiğiniz bir cihaz adı (varsayılan: cihaz modeli); böylece diğer cihazlarda nerede kaldığınızı görebilirsiniz.
- Tema ve okuyucu ayarları gibi **uygulama ayarları**. Bunlar cihazda kalır.

## 4. Verilerin aktarıldığı yerler

Veriler **yalnızca cihazınız ile uygulamaya girdiğiniz Nextcloud sunucusu arasında** ve her zaman HTTPS üzerinden şifreli olarak aktarılır. Bu sunucudaki işlemeden o sunucunun işletmecisi sorumludur: sunucuyu kendiniz işletiyorsanız siz, aksi halde bulut sağlayıcınız. Onların gizlilik politikası geçerlidir.

Veriler hiçbir zaman geliştiriciyle veya başka bir üçüncü tarafla paylaşılmaz.

Kitapların içindeki bağlantılar, yalnızca siz onayladıktan sonra cihazınızın tarayıcısında açılır. Ziyaret ettiğiniz web sitelerinin gizlilik politikaları orada geçerlidir.

## 5. İzinler

- **İnternet ve ağ durumu:** sunucunuza bağlantı; isterseniz indirmeler yalnızca Wi-Fi ile.
- **Bildirimler:** indirmelerin ilerleyişi ve tamamlanması. Bunları Android ayarlarından istediğiniz zaman kapatabilirsiniz.
- **Ön plan hizmeti (veri eşitleme):** büyük indirmeler uygulama arka plandayken de güvenilir şekilde sürer.

Uygulama konumunuza, kişilerinize, kameranıza, mikrofonunuza veya diğer dosyalarınıza erişmez.

## 6. Saklama ve silme

Siz silene kadar tüm veriler cihazda kalır:

- Uygulamadaki **“Hesabı kaldır”** işlemi, o hesabın tüm yerel verilerini (kütüphane, indirilen kitaplar ve kimlik bilgileri) siler ve sunucunuzdaki uygulama parolasını iptal eder. Yerel bir klasör için kütüphane, okuma konumu ve vurgular silinir ve klasör erişimi geri bırakılır; klasördeki kitap dosyaları korunur.
- Uygulamanın **kaldırılması** tüm yerel verileri siler.
- İndirilen kitaplar “İndirilenler” bölümünden istediğiniz zaman silinebilir.

Nextcloud sunucunuzdaki okuma konumu, vurgular ve raflar gibi verileri sunucunun kendisinde yönetirsiniz.

## 7. Hukuki dayanak

Cihaz üzerindeki işleme yalnızca talep ettiğiniz özellikleri sağlamaya yarar (GDPR madde 6(1)(b)). Geliştirici herhangi bir kişisel veriyi işlemez.

## 8. Haklarınız

GDPR kapsamında erişim, düzeltme, silme, işlemenin kısıtlanması, veri taşınabilirliği ve itiraz haklarına ve bir denetim makamına şikâyette bulunma hakkına sahipsiniz. Geliştirici sizden veri almadığı için, sunucuda saklanan veriler konusunda lütfen sunucunuzun işletmecisiyle iletişime geçin. Uygulamayla ilgili sorularınız için yukarıdaki e-posta adresini kullanın.

## 9. Çocuklar

Uygulama 16 yaşın altındaki çocuklara yönelik değildir.

## 10. Değişiklikler

Uygulama veya hukuki durum değiştiğinde bu politikayı güncelleriz. Burada yayımlanan sürüm geçerlidir.
