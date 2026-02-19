# Chaqmoq Platform

Multi-module Maven loyiha — bitta GitHub repoda bir nechta Telegram bot ishlaydi.

## Loyiha Tuzilmasi

```
chaqmoq-platform/           (parent POM, packaging: pom)
├── chaqmoq-common/          (umumiy modul — library JAR, executable emas)
├── chaqmoq-media-bot/       (media downloader bot, port 8081)
└── chaqmoq-birthday-bot/    (birthday bot, port 8080)
```

## Texnik Stack

- **Til:** Java 17
- **Framework:** Spring Boot 3.2.3
- **DB:** PostgreSQL (bitta DB, har bot o'z tablalari)
- **Telegram:** telegrambots-spring-boot-starter 6.9.7.1
- **Build:** Maven multi-module

## Modullar

### chaqmoq-common
- Umumiy kutubxona (library JAR) — spring-boot-maven-plugin ISHLATILMASIN
- Database konfiguratsiya
- Barcha modullar shu modulga depend qiladi

### chaqmoq-media-bot
- Media yuklab olish boti (YouTube, TikTok, Instagram, Facebook, Twitter/X, Snapchat, Pinterest, Threads, Likee)
- Audio MP3, Video, Dumaloq video formatlar
- Musiqa aniqlash (AudD API)
- Port: 8081

### chaqmoq-birthday-bot
- Tug'ilgan kun tabrik boti
- Port: 8080
- Hozircha placeholder — kod keyinroq ko'chiriladi

## Build Buyruqlari

```bash
# Barcha modullarni build qilish
mvn clean package -DskipTests

# Faqat bitta modulni build qilish
mvn clean package -DskipTests -pl chaqmoq-media-bot -am
mvn clean package -DskipTests -pl chaqmoq-birthday-bot -am

# Faqat common modulni compile qilish
mvn clean compile -pl chaqmoq-common
```

## Deploy

```bash
# Server tayyorlash (bir marta)
./setup-server.sh

# Systemd servicelarni o'rnatish (bir marta)
./install-services.sh

# Botlarni deploy qilish
SERVER_USER=ubuntu SERVER_HOST=your-server ./deploy-media-bot.sh
SERVER_USER=ubuntu SERVER_HOST=your-server ./deploy-birthday-bot.sh
```

## Muhim Qoidalar

1. **chaqmoq-common** da spring-boot-maven-plugin ISHLATILMASIN — bu library JAR
2. Har bir bot o'z portida ishlaydi (media: 8081, birthday: 8080)
3. Barcha versiyalar parent pom.xml dagi dependencyManagement da markazlashtirilgan
4. Environment o'zgaruvchilari orqali konfiguratsiya (`/etc/environment` yoki `.env`)
5. Yangi modul qo'shganda parent pom.xml dagi `<modules>` ga qo'shish kerak

## Environment O'zgaruvchilari

| O'zgaruvchi | Tavsif | Default |
|---|---|---|
| MEDIA_BOT_TOKEN | Media bot Telegram tokeni | - |
| MEDIA_BOT_USERNAME | Media bot username | ChaqmoqXBot |
| MEDIA_BOT_PORT | Media bot porti | 8081 |
| BIRTHDAY_BOT_TOKEN | Birthday bot Telegram tokeni | - |
| BIRTHDAY_BOT_USERNAME | Birthday bot username | YourBirthdayBot |
| BIRTHDAY_BOT_PORT | Birthday bot porti | 8080 |
| DATABASE_URL | PostgreSQL URL | jdbc:postgresql://localhost:5432/chaqmoq_platform |
| DB_USERNAME | DB foydalanuvchi | postgres |
| DB_PASSWORD | DB parol | password |
| YTDLP_PATH | yt-dlp yo'li | /usr/local/bin/yt-dlp |
| FFMPEG_PATH | ffmpeg yo'li | /usr/bin/ffmpeg |
| TEMP_DIR | Temp papka | /tmp/chaqmoq |
| AUDD_API_KEY | AudD API kaliti | - |
| MEDIA_ADMIN_IDS | Admin Telegram IDlari (vergul bilan) | 123456789 |
