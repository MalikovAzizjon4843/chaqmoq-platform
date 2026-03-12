package uz.chaqmoq.media.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import uz.chaqmoq.media.service.*;
import uz.chaqmoq.media.util.PlatformDetector;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class ChaqmoqBot extends TelegramLongPollingBot {

    private static final Logger logger = LoggerFactory.getLogger(ChaqmoqBot.class);

    private final UserService userService;
    private final MediaDownloadService mediaDownloadService;
    private final MusicRecognitionService musicRecognitionService;
    private final UrlCacheService urlCacheService;
    private final PlatformDetector platformDetector;

    @Value("${telegram.bot.username}")
    private String botUsername;

    private final String botToken;

    @Value("${admin.telegram.ids:}")
    private String adminIds;

    @Value("${audd.api.key:}")
    private String auddApiKey;

    public ChaqmoqBot(@Value("${telegram.bot.token}") String token,
                      UserService userService,
                      MediaDownloadService mediaDownloadService,
                      MusicRecognitionService musicRecognitionService,
                      UrlCacheService urlCacheService,
                      PlatformDetector platformDetector) {
        super(token);
        this.botToken = token;
        this.userService = userService;
        this.mediaDownloadService = mediaDownloadService;
        this.musicRecognitionService = musicRecognitionService;
        this.urlCacheService = urlCacheService;
        this.platformDetector = platformDetector;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            logger.error("Error handling update: {}", e.getMessage(), e);
        }
    }

    private void handleMessage(Message message) {
        userService.saveOrUpdate(message.getFrom());
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();

        if (userService.isBanned(userId)) {
            sendText(chatId, "⛔ Sizning akkauntingiz bloklangan.");
            return;
        }

        // Video xabar (dumaloq video) Shazam uchun
        if (message.hasVideoNote()) {
            try {
                handleMusicRecognitionFromVideoNote(message);
            } catch (TelegramApiException e) {
                logger.error("Error handling video note: {}", e.getMessage());
            }
            return;
        }

        // Video Shazam uchun (kichik videolar)
        if (message.hasVideo()) {
            try {
                handleMusicRecognitionFromVideo(message);
            } catch (TelegramApiException e) {
                logger.error("Error handling video: {}", e.getMessage());
            }
            return;
        }

        // Audio/Voice Shazam uchun
        if (message.hasAudio() || message.hasVoice()) {
            handleMusicRecognition(message);
            return;
        }

        if (message.hasText()) {
            String text = message.getText().trim();

            // Guruh va kanal uchun faqat URL ga javob ber
            boolean isGroup = message.getChat().isGroupChat() || message.getChat().isSuperGroupChat();

            if (isGroup) {
                // Guruhda faqat URL ga javob ber (spam oldini olish)
                if (platformDetector.isValidUrl(text)) {
                    handleDownloadRequest(chatId, text);
                }
                return;
            }

            // Private chatda buyruqlar va matn qidirish
            switch (text) {
                case "/start" -> handleStart(chatId, message.getFrom());
                case "/help", "❓ Yordam" -> handleHelp(chatId);
                case "/stats", "📊 Statistika" -> handleStats(chatId);
                case "/admin" -> handleAdmin(chatId, userId);
                case "📥 Video Yuklab olish" -> sendText(chatId,
                        "📥 *Video yuklab olish*\n\n" +
                        "Quyidagi platformalardan video havolasini yuboring:\n\n" +
                        "🎬 YouTube\n🎵 TikTok\n📸 Instagram\n📘 Facebook\n" +
                        "🐦 Twitter/X\n👻 Snapchat\n📌 Pinterest\n🧵 Threads\n❤️ Likee\n\n" +
                        "Havola yuboring va men yuklab beraman! ⚡");
                case "🎵 Musiqa Izlash" -> sendText(chatId,
                        "🎵 *Musiqa izlash*\n\n" +
                        "Menga audio yoki ovozli xabar yuboring, men musiqani aniqlab beraman!\n\n" +
                        "📌 Qo'shiq nomi, ijrochi va Spotify/Apple Music havolasini topaman.");
                default -> {
                    if (platformDetector.isValidUrl(text)) {
                        handleDownloadRequest(chatId, text);
                    } else {
                        // Matn orqali qo'shiq qidirish
                        if (auddApiKey != null && !auddApiKey.isEmpty()) {
                            try {
                                handleMusicSearch(chatId, text);
                            } catch (TelegramApiException e) {
                                logger.error("Error searching music: {}", e.getMessage());
                            }
                        } else {
                            sendText(chatId, "🤔 Havola yuboring yoki /help ni bosing");
                        }
                    }
                }
            }
        }
    }

    private void handleDownloadRequest(Long chatId, String url) {
        PlatformDetector.Platform platform = platformDetector.detect(url);

        if (platform == PlatformDetector.Platform.UNSUPPORTED) {
            sendText(chatId,
                    "⚠️ Bu platforma hozircha qo'llab-quvvatlanmaydi.\n\n" +
                            "✅ Ishlaydigan platformalar:\n" +
                            "🎬 YouTube\n📸 Instagram\n🎵 TikTok\n" +
                            "👤 Facebook\n🐦 X (Twitter)\n📌 Pinterest");
            return;
        }

        if (platform == PlatformDetector.Platform.UNKNOWN) {
            sendText(chatId, "❌ Bu platforma qo'llab-quvvatlanmaydi.\n\n" +
                    "Qo'llab-quvvatlanadigan platformalar:\n" +
                    "YouTube, Instagram, TikTok, Facebook, Twitter/X, Pinterest");
            return;
        }

        sendDownloadOptions(chatId, url, platform);
    }

    private void sendDownloadOptions(Long chatId, String url, PlatformDetector.Platform platform) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        boolean isYouTube = platform == PlatformDetector.Platform.YOUTUBE;

        if (isYouTube) {
            // YouTube uchun sifat tugmalari
            String video360 = urlCacheService.createCallbackData("dl_video_360", url);
            String video480 = urlCacheService.createCallbackData("dl_video_480", url);
            String video720 = urlCacheService.createCallbackData("dl_video_720", url);
            String video1080 = urlCacheService.createCallbackData("dl_video_1080", url);
            String audioCallback = urlCacheService.createCallbackData("dl_audio", url);
            String roundCallback = urlCacheService.createCallbackData("dl_round", url);

            // Qator 1: 360p, 480p, 720p
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            row1.add(InlineKeyboardButton.builder().text("📹 360p").callbackData(video360).build());
            row1.add(InlineKeyboardButton.builder().text("📹 480p").callbackData(video480).build());
            row1.add(InlineKeyboardButton.builder().text("📹 720p").callbackData(video720).build());
            rows.add(row1);

            // Qator 2: 1080p, Audio
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            row2.add(InlineKeyboardButton.builder().text("📹 1080p").callbackData(video1080).build());
            row2.add(InlineKeyboardButton.builder().text("🎵 Audio (MP3)").callbackData(audioCallback).build());
            rows.add(row2);

            // Qator 3: Dumaloq video
            List<InlineKeyboardButton> row3 = new ArrayList<>();
            row3.add(InlineKeyboardButton.builder().text("⭕ Video Xabar (Dumaloq)").callbackData(roundCallback).build());
            rows.add(row3);
        } else {
            // Boshqa platformalar uchun avvalgidek
            String videoCallback = urlCacheService.createCallbackData("dl_video", url);
            String audioCallback = urlCacheService.createCallbackData("dl_audio", url);
            String roundCallback = urlCacheService.createCallbackData("dl_round", url);

            // Qator 1: Video, Audio
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            row1.add(InlineKeyboardButton.builder()
                    .text("📹 Video")
                    .callbackData(videoCallback)
                    .build());
            row1.add(InlineKeyboardButton.builder()
                    .text("🎵 Audio (MP3)")
                    .callbackData(audioCallback)
                    .build());
            rows.add(row1);

            // Qator 2: Dumaloq video
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            row2.add(InlineKeyboardButton.builder()
                    .text("⭕ Video Xabar (Dumaloq)")
                    .callbackData(roundCallback)
                    .build());
            rows.add(row2);
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(platform.getEmoji() + " *" + platform.getName() + "* dan yuklab olish\n\n" +
                        "Qaysi formatda yuklab olmoqchisiz?")
                .parseMode("Markdown")
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            logger.error("Error sending download options: {}", e.getMessage());
        }
    }

    private void handleCallback(CallbackQuery callback) {
        String data = callback.getData();
        Long chatId = callback.getMessage().getChatId();
        Integer messageId = callback.getMessage().getMessageId();
        Long userId = callback.getFrom().getId();

        String url = urlCacheService.resolveUrl(data);
        if (url == null) {
            editMessage(chatId, messageId, "❌ Havola topilmadi. Qaytadan yuboring.");
            return;
        }

        String action = data.substring(0, data.indexOf(":"));

        switch (action) {
            case "dl_video" -> processDownload(chatId, messageId, userId, url, "video");
            case "dl_video_360" -> processDownload(chatId, messageId, userId, url, "video_360");
            case "dl_video_480" -> processDownload(chatId, messageId, userId, url, "video_480");
            case "dl_video_720" -> processDownload(chatId, messageId, userId, url, "video_720");
            case "dl_video_1080" -> processDownload(chatId, messageId, userId, url, "video_1080");
            case "dl_audio" -> processDownload(chatId, messageId, userId, url, "audio");
            case "dl_round" -> processDownload(chatId, messageId, userId, url, "round");
        }
    }

    private void processDownload(Long chatId, Integer messageId, Long userId, String url, String type) {
        PlatformDetector.Platform platform = platformDetector.detect(url);
        String typeName = switch (type) {
            case "audio" -> "audio (MP3)";
            case "round" -> "dumaloq video";
            case "video_360" -> "video (360p)";
            case "video_480" -> "video (480p)";
            case "video_720" -> "video (720p)";
            case "video_1080" -> "video (1080p)";
            default -> "video";
        };

        editMessage(chatId, messageId, "⏳ " + platform.getEmoji() + " " + typeName + " yuklanmoqda...\n\nBiroz kuting...");

        try {
            // Katta fayl bo'lishi mumkin bo'lgan holatlarda splitAndDownload ishlatish
            List<MediaDownloadService.DownloadResult> results =
                    mediaDownloadService.splitAndDownload(url, type);

            // Har bir qismni yuborish
            int total = results.size();

            // Ko'p qism bo'lsa foydalanuvchiga oldin xabar ber
            if (total > 1) {
                sendText(chatId,
                        "📦 Video " + total + " qismga bo'lindi.\n" +
                        "Navbat bilan yuboriladi, iltimos kuting...");
            }

            for (int i = 0; i < total; i++) {
                MediaDownloadService.DownloadResult result = results.get(i);
                if (!result.isSuccessful()) {
                    editMessage(chatId, messageId, "❌ " + result.getError());
                    userService.recordDownload(userId, url, platform.getName(), type, false, result.getError());
                    continue;
                }

                if (total > 1) {
                    editMessage(chatId, messageId, "📦 Qism " + (i+1) + "/" + total + " yuborilmoqda...");
                }

                try {
                    sendMediaFileWithCaption(chatId, result.getFile(), result.getTitle(), result.getMediaType(), i+1, total);
                    userService.recordDownload(userId, url, platform.getName(), type, true, null);
                } finally {
                    mediaDownloadService.cleanupFile(result.getFile());
                }
            }

            if (total > 0 && results.get(total - 1).isSuccessful()) {
                if (results.get(total - 1).getNotice() != null && !results.get(total - 1).getNotice().isBlank()) {
                    editMessage(chatId, messageId, "✅ " + platform.getEmoji() + " Muvaffaqiyatli yuklandi!\n\nℹ️ " + results.get(total - 1).getNotice());
                } else {
                    editMessage(chatId, messageId, "✅ " + platform.getEmoji() + " Muvaffaqiyatli yuklandi!");
                }
            }
        } catch (Exception e) {
            logger.error("Download processing error: {}", e.getMessage(), e);
            editMessage(chatId, messageId, "❌ Xatolik yuz berdi: " + e.getMessage());
            userService.recordDownload(userId, url, platform.getName(), type, false, e.getMessage());
        }
    }

    private void sendMediaFile(Long chatId, File file, String title, String type) throws TelegramApiException {
        InputFile inputFile = new InputFile(file);

        switch (type) {
            case "audio" -> {
                SendAudio sendAudio = SendAudio.builder()
                        .chatId(chatId.toString())
                        .audio(inputFile)
                        .title(title)
                        .caption("🎵 " + title)
                        .build();
                execute(sendAudio);
            }
            case "round" -> {
                SendVideoNote sendVideoNote = SendVideoNote.builder()
                        .chatId(chatId.toString())
                        .videoNote(inputFile)
                        .build();
                execute(sendVideoNote);
            }
            default -> {
                long fileSizeBytes = file.length();
                long limit = 50L * 1024 * 1024; // 50MB

                if (fileSizeBytes <= limit) {
                    // Kichik fayl — video sifatida
                    SendVideo send = new SendVideo();
                    send.setChatId(chatId.toString());
                    send.setVideo(inputFile);
                    send.setCaption("⚡ @" + botUsername);
                    send.setSupportsStreaming(true);
                    execute(send);
                } else {
                    // Katta fayl — document sifatida
                    // Foydalanuvchiga oldin xabar ber
                    sendText(chatId,
                            "📁 Video hajmi katta, fayl sifatida yuborilmoqda...");
                    SendDocument send = new SendDocument();
                    send.setChatId(chatId.toString());
                    send.setDocument(inputFile);
                    send.setCaption("⚡ @" + botUsername +
                            "\n📥 Yuklab olib, video pleyer bilan oching");
                    execute(send);
                }
            }
        }
    }

    private void sendMediaFileWithCaption(Long chatId, File file, String title, String type, int partNumber, int totalParts) throws TelegramApiException {
        InputFile inputFile = new InputFile(file);

        switch (type) {
            case "audio" -> {
                SendAudio sendAudio = SendAudio.builder()
                        .chatId(chatId.toString())
                        .audio(inputFile)
                        .title(title)
                        .caption("📦 " + partNumber + "/" + totalParts + " qism\n🎵 " + title)
                        .build();
                execute(sendAudio);
            }
            case "round" -> {
                SendVideoNote sendVideoNote = SendVideoNote.builder()
                        .chatId(chatId.toString())
                        .videoNote(inputFile)
                        .build();
                execute(sendVideoNote);
            }
            default -> {
                SendVideo sendVideo = SendVideo.builder()
                        .chatId(chatId.toString())
                        .video(inputFile)
                        .caption("📦 " + partNumber + "/" + totalParts + " qism\n⚡ @" + botUsername)
                        .supportsStreaming(true)
                        .build();
                execute(sendVideo);
            }
        }
    }

    private void handleMusicRecognition(Message message) {
        Long chatId = message.getChatId();
        sendText(chatId, "🎵 Musiqa aniqlanmoqda...");

        try {
            String fileId;
            if (message.hasAudio()) {
                fileId = message.getAudio().getFileId();
            } else {
                fileId = message.getVoice().getFileId();
            }

            File audioFile = downloadTelegramFile(fileId);
            if (audioFile == null) {
                sendText(chatId, "❌ Audio faylni yuklab olishda xato.");
                return;
            }

            MusicRecognitionService.MusicResult result = musicRecognitionService.recognizeFromFile(audioFile);
            mediaDownloadService.cleanupFile(audioFile);

            if (result.found) {
                String formatted = result.format();
                if (formatted != null && !formatted.isEmpty()) {
                    sendText(chatId, "🎵 *Musiqa topildi!*\n\n" + formatted);
                } else {
                    sendText(chatId, "❓ Musiqa ma'lumotlari to'liq emas.");
                }
            } else {
                sendText(chatId,
                        "❓ Musiqa tanib olinmadi.\n" +
                        "Boshqa audio yoki ovozli xabar yuboring.");
            }
        } catch (Exception e) {
            logger.error("Music recognition error: {}", e.getMessage(), e);
            sendText(chatId, "❌ Musiqa aniqlashda xato yuz berdi.");
        }
    }

    private void handleMusicRecognitionFromVideoNote(Message message) throws TelegramApiException {
        Long chatId = message.getChatId();
        sendText(chatId, "🎵 Video xabardan musiqa tanilmoqda...");
        try {
            String fileId = message.getVideoNote().getFileId();
            File videoFile = downloadTelegramFile(fileId);
            if (videoFile == null) {
                sendText(chatId, "❌ Fayl yuklab olinmadi");
                return;
            }
            MusicRecognitionService.MusicResult result =
                    musicRecognitionService.recognizeFromFile(videoFile);
            mediaDownloadService.cleanupFile(videoFile);

            if (result.found) {
                String formatted = result.format();
                if (formatted != null && !formatted.isEmpty()) {
                    sendText(chatId, "🎵 *Musiqa topildi!*\n\n" + formatted);
                } else {
                    sendText(chatId, "❓ Musiqa ma'lumotlari to'liq emas.");
                }
            } else {
                sendText(chatId, "❓ Musiqa tanib olinmadi. Boshqa audio yuboring.");
            }
        } catch (Exception e) {
            logger.error("Music recognition from video note failed", e);
            sendText(chatId, "❌ Musiqa tanishda xatolik");
        }
    }

    private void handleMusicRecognitionFromVideo(Message message) throws TelegramApiException {
        Long chatId = message.getChatId();
        // Video katta bo'lishi mumkin — faqat kichiklariga javob ber
        long fileSize = message.getVideo().getFileSize() != null ? message.getVideo().getFileSize() : 0;
        if (fileSize > 20 * 1024 * 1024) {
            sendText(chatId,
                    "⚠️ Video juda katta. Kichikroq video yuboring (max 20MB) yoki ovozli xabar yuboring.");
            return;
        }
        sendText(chatId, "🎵 Videodan musiqa tanilmoqda...");
        try {
            String fileId = message.getVideo().getFileId();
            File videoFile = downloadTelegramFile(fileId);
            if (videoFile == null) {
                sendText(chatId, "❌ Fayl yuklab olinmadi");
                return;
            }
            MusicRecognitionService.MusicResult result =
                    musicRecognitionService.recognizeFromFile(videoFile);
            mediaDownloadService.cleanupFile(videoFile);

            if (result.found) {
                String formatted = result.format();
                if (formatted != null && !formatted.isEmpty()) {
                    sendText(chatId, "🎵 *Musiqa topildi!*\n\n" + formatted);
                } else {
                    sendText(chatId, "❓ Musiqa ma'lumotlari to'liq emas.");
                }
            } else {
                sendText(chatId,
                        "❓ Musiqa tanib olinmadi.\n" +
                        "Ovozli xabar yoki audio fayl yuboring.");
            }
        } catch (Exception e) {
            logger.error("Music recognition from video failed", e);
            sendText(chatId, "❌ Musiqa tanishda xatolik");
        }
    }

    private void handleMusicSearch(Long chatId, String query) throws TelegramApiException {
        sendText(chatId, "🔍 Qo'shiq qidirilmoqda...");
        MusicRecognitionService.MusicResult result =
                musicRecognitionService.searchByText(query);
        if (result.found) {
            sendText(chatId, "🎵 *Topildi!*\n\n" + result.format());
        } else {
            sendText(chatId,
                    "❓ Qo'shiq topilmadi.\n" +
                    "Qo'shiq nomi, ijrochi yoki qo'shiq matni yuboring.");
        }
    }

    private File downloadTelegramFile(String fileId) {
        try {
            GetFile getFile = new GetFile(fileId);
            org.telegram.telegrambots.meta.api.objects.File telegramFile = execute(getFile);
            String filePath = telegramFile.getFilePath();

            String fileUrl = "https://api.telegram.org/file/bot" + getToken() + "/" + filePath;
            File tempFile = File.createTempFile("tg_audio_", ".ogg");

            try (InputStream is = new java.net.URL(fileUrl).openStream()) {
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            return tempFile;
        } catch (Exception e) {
            logger.error("Error downloading telegram file: {}", e.getMessage(), e);
            return null;
        }
    }

    private void handleStart(Long chatId, User user) {
        String name = user.getFirstName() != null ? user.getFirstName() : "Foydalanuvchi";
        String text = """
            🔥 *Assalomu alaykum, %s!*
            
            ⚡ *@%s* ga xush kelibsiz!
            
            📥 *Yuklab olish mumkin:*
            • 📸 Instagram — post, reel, stories
            • 🎵 TikTok — watermarksiz
            • 🎬 YouTube — video, shorts
            • 👤 Facebook — video
            • 🐦 X (Twitter) — video, gif
            • 📌 Pinterest — video, rasm
            
            🎵 *Shazam funksiyasi:*
            • Qo'shiq nomi yoki ijrochi ismi
            • Audio, ovozli xabar, video
            • TikTok/YouTube havolasi
            
            ⭕ *Round video* — dumaloq formatga o'girish
            
            😎 *Bot guruhlarda ham ishlaydi!*
            
            🚀 Havola yuboring — yuklab beraman!
            """.formatted(escapeMarkdown(name), botUsername);

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("Markdown");
        msg.setReplyMarkup(getMainKeyboard());

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            logger.error("Error sending start message: {}", e.getMessage());
        }
    }

    private void handleHelp(Long chatId) {
        String text = "📖 *Qo'llanma*\n\n" +
                "📥 *Video yuklab olish:*\n" +
                "Menga quyidagi platformalardan havola yuboring:\n" +
                "🎬 YouTube  🎵 TikTok  📸 Instagram\n" +
                "📘 Facebook  🐦 Twitter/X\n" +
                "📌 Pinterest\n\n" +
                "🎵 *Musiqa aniqlash:*\n" +
                "Audio yoki ovozli xabar yuboring — men musiqani topaman!\n\n" +
                "📋 *Buyruqlar:*\n" +
                "/start — Botni ishga tushirish\n" +
                "/help — Qo'llanma\n" +
                "/stats — Statistika";

        sendText(chatId, text);
    }

    private void handleStats(Long chatId) {
        long totalUsers = userService.getTotalUsers();
        long totalDownloads = userService.getTotalDownloads();

        String text = "📊 *Statistika*\n\n" +
                "👥 Jami foydalanuvchilar: *" + totalUsers + "*\n" +
                "📥 Jami yuklab olishlar: *" + totalDownloads + "*";

        sendText(chatId, text);
    }

    private void handleAdmin(Long chatId, Long userId) {
        if (!isAdmin(userId)) {
            sendText(chatId, "⛔ Bu buyruq faqat adminlar uchun.");
            return;
        }

        long totalUsers = userService.getTotalUsers();
        long activeUsers = userService.getActiveUsers();
        long totalDownloads = userService.getTotalDownloads();

        String text = "🔐 *Admin Panel*\n\n" +
                "👥 Jami foydalanuvchilar: *" + totalUsers + "*\n" +
                "✅ Aktiv foydalanuvchilar: *" + activeUsers + "*\n" +
                "📥 Jami yuklab olishlar: *" + totalDownloads + "*";

        sendText(chatId, text);
    }

    private ReplyKeyboardMarkup getMainKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("📥 Video Yuklab olish"));
        row1.add(new KeyboardButton("🎵 Musiqa Izlash"));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("📊 Statistika"));
        row2.add(new KeyboardButton("❓ Yordam"));

        ReplyKeyboardMarkup markup = ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row1, row2))
                .resizeKeyboard(true)
                .isPersistent(true)
                .build();

        return markup;
    }

    private boolean isAdmin(Long userId) {
        if (adminIds == null || adminIds.isBlank()) return false;
        return Arrays.stream(adminIds.split(","))
                .map(String::trim)
                .anyMatch(id -> id.equals(userId.toString()));
    }

    private void sendText(Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            logger.error("Error sending message: {}", e.getMessage());
        }
    }

    private void editMessage(Long chatId, Integer messageId, String text) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .parseMode("Markdown")
                .build();
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            logger.error("Error editing message: {}", e.getMessage());
        }
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }

    private String getToken() {
        return botToken;
    }
}
