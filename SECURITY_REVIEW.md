# DonutAuctions Security Review (v1.0.0, Updated)

## Attack Surface Haritası

### Event yüzeyi
- Giriş noktaları: `InventoryClick`, `InventoryDrag`, `InventoryClose`, `PlayerQuit`, `AsyncPlayerChat`
- Risk: GUI manipülasyonu, click/drag spam, state desync
- Savunma: holder doğrulaması, secure default cancel, kontrollü whitelist, click cooldown, close/quit state cleanup

### Komut yüzeyi
- Giriş noktaları: `/ah`, `/ah sell`, `/ah my`, `/ah transactions`, `/ah reload`
- Risk: malformed input, permission abuse, ekonomik manipülasyon
- Savunma: permission checks, numeric/finiteness/range validation, amount validation, UltimateShop sellability/forced-price policies

### GUI/menü yüzeyi
- Giriş noktaları: custom controls (`action` PDC), listing id (`listing-id` PDC)
- Risk: click-type bypass, slot/state bozulması
- Savunma: `InventoryHolder` doğrulaması, slot bound check, action whitelist, failure-safe close

### Veri katmanı
- Giriş noktaları: `auction-data.yml`, autosave scheduler, backup rotation
- Risk: veri kaybı, bozuk dosya, yarım commit
- Savunma: atomic write (tmp + fsync + replace), schema + commit marker, bozuk dosyada backup restore

### Ağ/packet yüzeyi
- Risk: high ping, packet reorder, double interaction
- Savunma: server-authoritative transaction, synchronized service methods, listing removal yarışını önleyen akış

### 3. parti entegrasyonlar
- Vault, UltimateShop
- Risk: API signature drift, soft-dependency eksikliği, command abuse
- Savunma: reflective compatibility strategy, null-safe fallback, command sanitization (`%material%`, blocked separators)

## Bulgu Listesi

1) **Kritiklik: High**
- Etki: Folia üzerinde scheduler kaynaklı enable/runtime kırılması
- Kök neden: scheduler uyumluluğu ve reload sonrası runtime task periyodunun stale kalabilmesi
- Fix: Folia async scheduler + Bukkit fallback; `/ah reload` sonrası autosave scheduler reschedule
- Regression riski: Düşük

2) **Kritiklik: High**
- Etki: GUI üzerinden manipülasyon/desync sınıfı
- Kök neden: GUI açıkken tüm click tiplerinin doğru ayrıştırılamaması riski
- Fix: default-cancel + top inventory whitelist + bottom inventory sadece güvenli aksiyonlar
- Regression riski: Düşük/Orta (çok agresif kilitleme UX'i etkileyebilir)

3) **Kritiklik: High**
- Etki: ekonomi bütünlüğü / dupe sınıfı risk
- Kök neden: transaction akışında doğrulama/rollback eksikliği olasılığı
- Fix: `purchase` tek transaction akışı, başarısız transferlerde güvenli geri dönüş, listing bazlı tekil commit
- Regression riski: Düşük

4) **Kritiklik: Medium**
- Etki: stack fiyatlamada yanlış toplam (haksız kazanç veya yanlış fiyat)
- Kök neden: birim yerine stack üzerinden öneri ve multiplier uygulanması
- Fix: birim fiyat + bağımsız enchant bonusu + `* amount` modeline geçiş
- Regression riski: Düşük

5) **Kritiklik: Medium**
- Etki: komut davranışı belirsizliği (`force-recommended-price` açıkken amount parse)
- Kök neden: argüman düzeni tek format varsayımı
- Fix: force modunda hem `/ah sell <amount>` hem `/ah sell <ignoredPrice> <amount>` desteği
- Regression riski: Düşük

6) **Kritiklik: Medium**
- Etki: veri kaybı dayanıklılığı zayıflığı
- Kök neden: bozuk primary dosyada otomatik recovery eksikliği
- Fix: latest backup restore path eklendi
- Regression riski: Düşük

7) **Kritiklik: Medium**
- Etki: DoS/memory baskısı (aşırı lore/meta)
- Kök neden: listelenen item meta sınırlarının olmaması
- Fix: display name/lore/PDC key limitleri ve validation
- Regression riski: Orta (aşırı meta itemler reddedilir)

## Kod Değişiklik Stratejisi

- Transaction ve idempotency: merkezi service akışı, synchronized state transitions
- GUI fail-safe: cancel-by-default, whitelist-only işlem modeli
- Durability: atomic persistence + backup rotation + restore
- API uyumluluk: UltimateShop için yeni/eski method signature destekleri
- Runtime stability: reload sonrası scheduler yeniden planlama

## Dupe/Exploit Senaryo Matrisi (Soyut)

- Yüksek ping / packet reorder
  - Etki: state desync
  - Kök neden: istemci tarafına güvenme
  - Fix: server-authoritative commit + single service gate
  - Test: gecikmeli ortamda aynı listing çoklu etkileşim

- Auto-click / drag spam
  - Etki: işlem seli, lag
  - Kök neden: rate-limit eksikliği
  - Fix: click cooldown + drag cancel
  - Test: burst click/drag with macro

- Shift/number/offhand manipülasyonu
  - Etki: GUI bypass sınıfı
  - Kök neden: event türlerinin eksik kapsanması
  - Fix: yasak click türleri bloklu, whitelist aksiyonlar sınırlı
  - Test: tüm click türleri matrisi

- Ölüm/respawn/teleport/world change
  - Etki: stale GUI state, item akışı bozulması
  - Kök neden: lifecycle edge-case yönetimi
  - Fix: close/quit cleanup + server-authoritative item movement
  - Test: açık GUI ile ölüm/ışınlanma/oyundan çıkış

- Reload/disable/stop sırasında yarım işlem
  - Etki: veri kaybı veya state orphan
  - Kök neden: task/scheduler flush eksikliği
  - Fix: onDisable shutdown, autosave cancel, save flush, reload reschedule
  - Test: reload-stop-kill altı persist doğrulaması

- Beklenmedik exception
  - Etki: menü ve işlem akışı kırılması
  - Kök neden: kritik handler guard eksikliği
  - Fix: try/catch fail-safe, rate-limited warning, safe close
  - Test: controlled exception injection

## Test Matrisi

1. GUI click türleri: normal, shift, number key, offhand, double click, drag
2. Eşzamanlı satın alma: iki oyuncu tek listing
3. Yüksek ping ve düşük TPS altında listing/purchase
4. Quick-list sürükle-bırak (full stack / partial stack)
5. `/ah sell <fiyat> <miktar>` valid/invalid amount durumları
6. Force-suggested modunda amount parse formatları
7. Reload sırasında açık menü ve scheduler davranışı
8. Crash/kill sonrası `auction-data.yml` + backup restore doğrulaması
9. Bozuk veri dosyasıyla açılış
10. UltimateShop offline/online ve API fallback testleri

## Performans Analizi

- Listing görüntüleme: O(n log n) sort + O(n) filter
- Transaction listesi bounded (`transactions-max`)
- Autosave periyodu config-driven
- Log spam rate-limited
- Meta kontrolü O(lore-size), sabit sınırlarla bounded

## Patch Sonrası Dupe/Desync Yeniden Değerlendirme

- Cancel mantığı: default deny, whitelist allow; yeni bypass gözlenmedi.
- Rollback: ekonomi başarısızlığı senaryolarında güvenli geri dönüş korunuyor.
- Idempotency: listing-level transactional akış + click cooldown korunuyor.
- Thread geçişi: Bukkit API kritik işlemleri main-thread uyumlu pathlerde; async yalnız I/O/scheduler katmanında.

Sonuç: mevcut patch seti yeni bir dupe/desync sınıfı açmadan güvenlik, kararlılık ve dayanıklılığı artırıyor. Kalan riskler büyük ölçüde 3. parti ekonomi/mağaza sağlayıcılarının davranışına bağlıdır.
