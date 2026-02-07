# DonutAuctions 1.0.0 Security Review (Spigot/Paper/Purpur/Folia 1.21.11)

## 1) Attack Surface Haritasi

### Event katmani
- Giris noktasi: `InventoryClick`, `InventoryDrag`, `InventoryClose`, `PlayerQuit`, `AsyncPlayerChat`
- Risk: GUI manipulasyonu, state desync, spam/DoS, async thread misuse
- Savunma:
  - GUI acikken default `cancel=true`
  - Holder dogrulamasi (`AuctionMenuHolder`)
  - Drag tam blok + quick-list slotu icin kontrollu whitelist
  - Async chat girdisi sync scheduler ile isleniyor
  - Quit/close lifecycle temizligi

### Komut katmani
- Giris noktasi: `/ah`, `/ah sell`, `/ah my`, `/ah transactions`, `/ah reload`
- Risk: permission bypass, hatali fiyat/veri, command spam
- Savunma:
  - Permission kontrolleri
  - Numeric + finite + min/max fiyat validasyonu
  - UltimateShop sellable zorlamasi (opsiyonel)
  - UltimateShop suggested-price zorlamasi (opsiyonel)

### GUI/Menu katmani
- Giris noktasi: custom control item aksiyonlari (`actionKey`), listing id (`listingKey`)
- Risk: click type abuse, slot tasmasi, item movement desync
- Savunma:
  - Slot bounds kontrolu
  - Rate-limited click throttle
  - Action whitelist mantigi
  - Menulerde fallback/fail-safe close

### Veri katmani
- Giris noktasi: `auction-data.yml`, autosave, backup
- Risk: crash sirasinda veri kaybi, bozuk dosya, yarim write
- Savunma:
  - Atomic write (tmp + fsync + move)
  - Commit marker + schema version
  - Backup rotation
  - Bozuk/eksik commit dosyasinda latest backup restore

### Ag/packet katmani
- Risk: high ping, paket gecikmesi, GUI state uyumsuzlugu
- Savunma:
  - Server-authoritative commit akisi
  - `synchronized` transaction metotlari
  - click cooldown

### 3. parti entegrasyonlar
- Vault, UltimateShop
- Risk: provider yoklugu, API degisikligi, command chain riski
- Savunma:
  - Soft-depend + ready checks
  - Reflection fallback
  - Dynamic command sanitize (`%material%` temizleme + tehlikeli ayirac bloklama)

## 2) Bulgu Listesi (Kritiklik / Etki / Kok Neden / Fix / Regression)

1. Kritiklik: High
- Etki: Folia enable crash
- Kok neden: Folia'da Bukkit async timer kullanimi
- Fix: Folia AsyncScheduler kullanimi + Bukkit fallback
- Regression riski: Dusuk

2. Kritiklik: High
- Etki: GUI desync / dupe sinifi item hareket manip.
- Kok neden: GUI acikken alt inventory etkileşim ihtimali
- Fix: secure default cancel + holder dogrulama + drag kapsami
- Regression riski: Dusuk

3. Kritiklik: High
- Etki: ekonomi tutarsizligi/double-spend sinifi
- Kok neden: satin alma adimlarinin daginik olmasi
- Fix: `AuctionService.purchase()` transaction modeli (validate -> withdraw -> deposit -> remove -> log)
- Regression riski: Dusuk

4. Kritiklik: Medium
- Etki: crash/restart sonrasi veri kaybi
- Kok neden: bozuk primary dosyada geri donus mekanizmasi eksikligi
- Fix: backup rotation + bozuk committe latest backup restore
- Regression riski: Dusuk

5. Kritiklik: Medium
- Etki: lag/DoS sinifi metadata sismirme
- Kok neden: listelenen item meta/lore/PDC sinirlari yoktu
- Fix: createListing icinde meta guvenlik limitleri
- Regression riski: Orta (asiri metali itemler listeye alinmaz)

6. Kritiklik: Medium
- Etki: config command zinciri abuse sinifi
- Kok neden: dynamic-sale-command stringlerinin yetersiz filtrelenmesi
- Fix: tehlikeli karakter bloklama + sanitize + console authoritative dispatch
- Regression riski: Dusuk

7. Kritiklik: Medium
- Etki: GUI action state stale kalmasi (filter degisince gorunum yenilenmemesi)
- Kok neden: filter action sonrası menu refresh eksigi
- Fix: filter cycle sonrası aninda `openAuction`
- Regression riski: Dusuk

8. Kritiklik: Low
- Etki: UX / state tutarsizligi
- Kok neden: your-items drag quick-list slotunun event katmaninda acik secik yakalanmamasi
- Fix: `InventoryDrag` icinde quick-list slotu whitelisti
- Regression riski: Dusuk

## 3) Uygulanan Kod Degisiklik Stratejisi

- Thread ve scheduler:
  - Folia uyumlu autosave scheduler
  - Async chat -> sync commit handoff

- Transaction ve idempotency:
  - Satin alma tek transaction metodunda
  - Operation cache + click cooldown ile tekrar islem azaltildi

- GUI fail-safe:
  - Cancel by default
  - Holder ve slot dogrulamasi
  - Drag event kapsami

- Durability:
  - Atomic write
  - Backup rotation
  - Corrupt primary dosyada backup restore

- Input/metadata guvenligi:
  - Fiyat ve sayisal validasyonlar
  - Item meta/lore/PDC limitleri
  - Dynamic command sanitization

## 4) Dupe/Exploit Senaryo Matrisi (Soyut)

- Yuksek ping / paket sirasi bozulmasi
  - Etki: state desync sinifi
  - Kok neden: client-side goruntuye guvenme
  - Fix: server-authoritative listing/purchase commit
  - Test: gecikmeli baglanti + ayni anda coklu click

- Macro / drag spam
  - Etki: DoS ve tekrarli islem sinifi
  - Kok neden: throttle yoklugu
  - Fix: click cooldown + drag cancel
  - Test: otomasyonla burst click/drag

- Shift/number/offhand manip.
  - Etki: GUI bypass sinifi
  - Kok neden: event kapsami eksigi
  - Fix: GUI acikken tum click default cancel
  - Test: tum click tiplerini matrix halinde dene

- Death/respawn/teleport/world change
  - Etki: menu-state orphan, item kaybi sinifi
  - Kok neden: lifecycle baglari zayif olabilir
  - Fix: menu close fail-safe + commit server-side
  - Test: menu acikken olum/isınlanma dunyadegisimi

- Plugin reload/disable/server stop
  - Etki: yarim islem/veri kaybi sinifi
  - Kok neden: pending write flush eksigi
  - Fix: onDisable shutdown + save + task cancel + menu close
  - Test: reload ve stop senaryolari altinda veri tutarlilik kontrolu

- Beklenmedik exception
  - Etki: state yarim kalma
  - Kok neden: handler korumalari eksigi
  - Fix: kritik click path try/catch + safe close + rate-limited log
  - Test: controlled exception injection

## 5) Test Matrisi

1. GUI click tipi matrisi: normal, shift, number-key, double-click, drag
2. Iki oyuncu ayni ilani eszamanli satin alma
3. Yuksek ping simulasyonu altinda listeleme/satin alma
4. TPS dusukken click burst + search spam
5. Reload/disable sirasinda acik menuler
6. Kill/stop aninda autosave flush davranisi
7. Bozuk `auction-data.yml` ile acilis (backup restore dogrulamasi)
8. UltimateShop aktif/pasif + force price + only-sellable kombinasyonlari
9. Asiri lore/PDC meta item listeleme denemeleri
10. Dynamic sale command sanitize denemeleri

## 6) Performans Analizi

- Listing goruntuleme: O(n log n) sort + O(n) filter
- Transaction cache: TTL cleanup ile bounded
- Transaction kayit listesi: config ile bounded (`transactions-max`)
- Autosave periyodik ve ayarlanabilir (`storage.autosave-ticks`)
- Log spam: rate-limited warning

## 7) Patch Sonrasi Yeni Dupe/Desync Acabilir mi?

- Cancel mantigi: Yeni patch GUI tarafinda daha kati, yeni bypass acmiyor.
- Rollback: Ekonomi rollback yolunda ek transfer adimlari degismedi; listing kaynagi synchronized.
- Idempotency: Operation key + listing removal + cooldown kombinasyonu korunuyor.
- Thread gecisi: Bukkit API commitleri sync pathte; async yalnizca I/O/chat bridge.

Sonuc: Yeni patch setinde yeni bir dupe/desync sinifi acildigina dair bulgu yok; kalan riskler daha cok ekonomi provider davranisina ve 3. parti plugin API farkliliklarina bagli.
