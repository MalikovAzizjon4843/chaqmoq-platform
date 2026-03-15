package uz.chaqmoq.media.handler;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final ExecutorService downloadExecutor = 
        Executors.newFixedThreadPool(5); // 5 ta parallel yuklab olish

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
            try {
                switch (text) {
                    case "/start" -> handleStart(chatId, message.getFrom());
                    case "/help", "❓ Yordam" -> handleHelp(chatId);
                    case "/stats", "📊 Statistika" -> handleStats(chatId);
                    case "/admin" -> handleAdmin(chatId, userId);
                    case "📥 Video Yuklab olish" -> handleVideoHelp(chatId);
                    case "🎵 Musiqa Izlash" -> handleMusicHelp(chatId);
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
            } catch (TelegramApiException e) {
                logger.error("Error handling message: {}", e.getMessage());
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
            // Boshqa platformalar uchun
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
            sendText(chatId, "❌ Havola topilmadi. Qaytadan yuboring.");
            return;
        }

        String action = data.substring(0, data.indexOf(":"));

        switch (action) {
            case "dl_video" -> processDownload(chatId, userId, url, "video");
            case "dl_video_360" -> processDownload(chatId, userId, url, "video_360");
            case "dl_video_480" -> processDownload(chatId, userId, url, "video_480");
            case "dl_video_720" -> processDownload(chatId, userId, url, "video_720");
            case "dl_video_1080" -> processDownload(chatId, userId, url, "video_1080");
            case "dl_audio" -> processDownload(chatId, userId, url, "audio");
            case "dl_round" -> processDownload(chatId, userId, url, "round");
        }
    }

    private void processDownload(Long chatId, Long userId, String url, String type) {
        downloadExecutor.submit(() -> {
            try {
                processDownloadInternal(chatId, userId, url, type);
            } catch (Exception e) {
                logger.error("Download error", e);
                try {
                    sendText(chatId, "❌ Yuklab olishda xatolik yuz berdi.");
                } catch (Exception ex) {
                    logger.error("Could not send error message", ex);
                }
            }
        });
    }

    private void processDownloadInternal(Long chatId, Long userId, String url, String type) throws TelegramApiException {
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

        try {
            // 1. Progress xabarini yuborish
            Message progressMsg = sendProgressMessage(chatId, type);
            
            List<MediaDownloadService.DownloadResult> results;
            
            // Round video uchun alohida metod
            if ("round".equals(type)) {
                logger.info("processDownload: round type detected, calling downloadAsRoundVideo()");
                MediaDownloadService.DownloadResult roundResult = 
                    mediaDownloadService.downloadAsRoundVideo(url);
                results = List.of(roundResult);
            } else if ("audio".equals(type)) {
                // Audio uchun downloadAudio()
                logger.info("processDownload: audio type detected");
                MediaDownloadService.DownloadResult audioResult = 
                    mediaDownloadService.downloadAudio(url);
                results = List.of(audioResult);
            } else {
                // Video types: video, video_360, video_480, video_720, video_1080
                logger.info("processDownload: video type={} detected, calling downloadVideo()", type);
                MediaDownloadService.DownloadResult videoResult = 
                    mediaDownloadService.downloadVideo(url, type);
                results = List.of(videoResult);
            }

            // 3. Progress xabarini o'chirish
            deleteMessage(chatId, progressMsg.getMessageId());

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
                    String errorText = getErrorText(result.getError());
                    sendText(chatId, errorText);
                    userService.recordDownload(userId, url, platform.getName(), type, false, result.getError());
                    continue;
                }

                if (total > 1) {
                    sendText(chatId, "📦 Qism " + (i+1) + "/" + total + " yuborilmoqda...");
                }

                try {
                    logger.info("processDownload: mediaType={}, fileSize={}MB",
                        result.getMediaType(),
                        result.getFile().length() / 1024 / 1024);
                    sendMediaFileWithCaption(chatId, result.getFile(), result.getTitle(), result.getMediaType(), i+1, total);
                    userService.recordDownload(userId, url, platform.getName(), type, true, null);
                } finally {
                    mediaDownloadService.cleanupFile(result.getFile());
                }
            }

            if (total > 0 && results.get(total - 1).isSuccessful()) {
                if (results.get(total - 1).getNotice() != null && !results.get(total - 1).getNotice().isBlank()) {
                    sendText(chatId, "✅ " + platform.getEmoji() + " Muvaffaqiyatli yuklandi!\n\nℹ️ " + results.get(total - 1).getNotice());
                } else {
                    sendText(chatId, "✅ " + platform.getEmoji() + " Muvaffaqiyatli yuklandi!");
                }
            }
        } catch (Exception e) {
            logger.error("Download processing error: {}", e.getMessage(), e);
            sendText(chatId, "❌ Xatolik yuz berdi: " + e.getMessage());
            userService.recordDownload(userId, url, platform.getName(), type, false, e.getMessage());
        }
    }

    private void sendMediaFile(Long chatId, File file, String title, String type) throws TelegramApiException {
        logger.info("Sending media: type={}, size={}MB, file={}",
            type,
            file.length() / 1024 / 1024,
            file.getName());
        
        InputFile inputFile = new InputFile(file);

        switch (type) {
            case "round" -> {
                logger.info("Sending round video, size={}MB", 
                    file.length() / 1024 / 1024);
                SendVideoNote send = new SendVideoNote();
                send.setChatId(chatId.toString());
                send.setVideoNote(new InputFile(file));
                execute(send);
            }
            case "audio" -> {
                SendAudio send = new SendAudio();
                send.setChatId(chatId.toString());
                send.setAudio(inputFile);
                send.setTitle(title);
                send.setCaption("✅ " + getTypeEmoji("audio") + " | ⚡ @" + botUsername);
                execute(send);
            }
            case "video" -> sendVideoFile(chatId, file, "✅ " + getTypeEmoji("video") + " | ⚡ @" + botUsername);
            default -> sendVideoFile(chatId, file, "✅ " + getTypeEmoji(type) + " | ⚡ @" + botUsername);
        }
    }

    private void sendMediaFileWithCaption(Long chatId, File file, String title, String type, int partNumber, int totalParts) throws TelegramApiException {
        InputFile inputFile = new InputFile(file);

        switch (type) {
            case "round" -> {
                logger.info("Sending round video, size={}MB", 
                    file.length() / 1024 / 1024);
                SendVideoNote send = new SendVideoNote();
                send.setChatId(chatId.toString());
                send.setVideoNote(new InputFile(file));
                execute(send);
            }
            case "audio" -> {
                SendAudio send = new SendAudio();
                send.setChatId(chatId.toString());
                send.setAudio(inputFile);
                send.setTitle(title);
                String caption = totalParts > 1 ? 
                    "📦 " + partNumber + "/" + totalParts + " qism\n🎵 " + title :
                    "✅ " + getTypeEmoji("audio") + " | ⚡ @" + botUsername;
                send.setCaption(caption);
                execute(send);
            }
            case "video" -> {
                String caption = totalParts > 1 ? 
                    "📦 " + partNumber + "/" + totalParts + " qism\n⚡ @" + botUsername :
                    "✅ " + getTypeEmoji("video") + " | ⚡ @" + botUsername;
                sendVideoFile(chatId, file, caption);
            }
            default -> {
                String caption = totalParts > 1 ? 
                    "📦 " + partNumber + "/" + totalParts + " qism\n⚡ @" + botUsername :
                    "✅ " + getTypeEmoji(type) + " | ⚡ @" + botUsername;
                sendVideoFile(chatId, file, caption);
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
        String firstName = user.getFirstName() != null ? user.getFirstName() : "Foydalanuvchi";
        String text = "⚡ Assalomu alaykum, " + firstName + "!\n\n" +
            "🎬 Men sizga ijtimoiy tarmoqlardan video va " +
            "musiqa yuklab olishda yordam beraman!\n\n" +
            "📥 Ishlaydigan platformalar:\n" +
            "• 🎬 YouTube — video, shorts\n" +
            "• 📸 Instagram — post, reel\n" +
            "• 🎵 TikTok — watermarksiz\n" +
            "• 👤 Facebook — video\n" +
            "• 🐦 Twitter/X — video, gif\n" +
            "• 📌 Pinterest — video, rasm\n\n" +
            "🎵 Shazam — musiqa tanish:\n" +
            "• Audio, video, ovozli xabar yuboring\n" +
            "• Qo'shiq nomi yoki ijrochi yozing\n\n" +
            "⭕ Round video — dumaloq formatga o'girish\n\n" +
            "😎 Bot guruhlarda ham ishlaydi!\n\n" +
            "🚀 Havola yuboring — yuklab beraman!";

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setReplyMarkup(getMainKeyboard());

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            logger.error("Error sending start message: {}", e.getMessage());
        }
    }

    private void handleVideoHelp(Long chatId) {
        String text = """
        🎬 *Video yuklab olish*
        
        Quyidagi platformalardan video havolasini yuboring:
        
        🎬 YouTube — video, shorts
        📸 Instagram — post, reel
        🎵 TikTok — watermarksiz
        👤 Facebook — video
        🐦 Twitter/X — video, gif
        📌 Pinterest — video, rasm
        
        Havola yuboring — men yuklab beraman! ⚡
        """;

        sendText(chatId, text);
    }

    private void handleMusicHelp(Long chatId) {
        String text = """
        🎵 *Musiqa izlash*
        
        Quyidagilardan birini yuboring:
        • 🎤 Audio fayl yoki ovozli xabar
        • 🎬 Video (musiqasini taniydi)
        • ⭕ Video xabar (dumaloq)
        • ✍️ Qo'shiq nomi yoki ijrochi ismi
        
        Men qo'shiq nomini, ijrochini va 
        Spotify/YouTube havolasini topaman! 🎧
        """;

        sendText(chatId, text);
    }

    private void handleHelp(Long chatId) {
        String text = """
        📖 *Qo'llanma*
        
        📥 *Video yuklab olish:*
        Menga quyidagi platformalardan havola yuboring:
        • 🎬 YouTube  📸 Instagram  🎵 TikTok
        • 👤 Facebook  🐦 Twitter/X  📌 Pinterest
        
        🎵 *Musiqa aniqlash (Shazam):*
        • Audio yoki ovozli xabar yuboring
        • Video yuboring — musiqasini topaman
        • Qo'shiq nomi yoki ijrochi ismini yozing
        
        ⭕ *Round video:*
        Video havolasini yuboring → Round Video tugmasini bosing
        
        📋 *Buyruqlar:*
        /start — Botni ishga tushurish
        /help — Qo'llanma
        /stats — Statistika
        
        😎 *Bot guruhlarda ham ishlaydi!*
        """;

        sendText(chatId, text);
    }

    private void handleStats(Long chatId) throws TelegramApiException {
        long totalUsers = userService.getTotalUsers();
        long totalDownloads = userService.getTotalDownloads();
        long todayDownloads = userService.getTodayDownloads();
        long successDownloads = userService.getSuccessDownloads();
        long failedDownloads = userService.getFailedDownloads();

        String text = "📊 Bot Statistikasi\n\n" +
            "👥 Foydalanuvchilar:\n" +
            "• Jami: " + totalUsers + " nafar\n\n" +
            "📥 Yuklab olishlar:\n" +
            "• Jami: " + totalDownloads + " ta\n" +
            "• Bugun: " + todayDownloads + " ta\n" +
            "• Muvaffaqiyatli: " + successDownloads + " ta\n" +
            "• Xatolik: " + failedDownloads + " ta\n\n" +
            "⚡ @" + botUsername;

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        execute(msg);
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
        row1.add("📥 Video Yuklab olish");
        row1.add("🎵 Musiqa Izlash");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📊 Statistika");
        row2.add("❓ Yordam");

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1, row2));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);
        return keyboard;
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

    private void sendVideoFile(Long chatId, File file, String caption) throws TelegramApiException {
        SendVideo send = new SendVideo();
        send.setChatId(chatId.toString());
        send.setVideo(new InputFile(file));
        send.setCaption(caption);
        send.setSupportsStreaming(true);
        execute(send);
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }

    private Message sendProgressMessage(Long chatId, String type) 
            throws TelegramApiException {
        String emoji;
        String text;
        switch (type) {
            case "audio" -> {
                emoji = "🎵";
                text = "Audio yuklanmoqda...";
            }
            case "round" -> {
                emoji = "⭕";
                text = "Dumaloq video tayyorlanmoqda...";
            }
            case "video_1080" -> {
                emoji = "🎬";
                text = "1080p video yuklanmoqda...";
            }
            case "video_720" -> {
                emoji = "🎬";
                text = "720p video yuklanmoqda...";
            }
            case "video_480" -> {
                emoji = "🎬";
                text = "480p video yuklanmoqda...";
            }
            case "video_360" -> {
                emoji = "🎬";
                text = "360p video yuklanmoqda...";
            }
            default -> {
                emoji = "📥";
                text = "Yuklanmoqda...";
            }
        }
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(emoji + " " + text);
        return execute(msg);
    }

    private String getErrorText(String msg) {
        if (msg == null) return "❌ Yuklab olishda xatolik.";
        
        if (msg.contains("50MB dan katta")) {
            return "⚠️ Video hajmi 50MB dan katta.\n\n" +
                "💡 Pastroq sifat tanlang:\n" +
                "360p yoki 480p ni sinab ko'ring";
        } else if (msg.contains("timeout") || msg.contains("vaqti tugadi")) {
            return "⏱ Yuklab olish vaqti tugadi.\n\n" +
                "💡 Maslahat:\n" +
                "• Pastroq sifat tanlang\n" +
                "• Keyinroq qayta urinib ko'ring";
        } else if (msg.contains("Private") || msg.contains("private")) {
            return "🔒 Bu video yopiq (private).\n" +
                "Faqat ochiq videolarni yuklab olish mumkin.";
        } else if (msg.contains("age") || msg.contains("yosh chegarasi")) {
            return "🔞 Bu video yosh chegarasi bilan cheklangan.";
        } else if (msg.contains("not found") || msg.contains("topilmadi")) {
            return "❌ Video topilmadi.\n" +
                "Havola to'g'riligini tekshiring.";
        } else {
            return "❌ Yuklab olishda xatolik.\n\n" +
                "💡 Maslahat:\n" +
                "• Havolani tekshiring\n" +
                "• Keyinroq qayta urinib ko'ring\n" +
                "• Pastroq sifat tanlang";
        }
    }

    private String getTypeEmoji(String type) {
        return switch (type) {
            case "audio" -> "🎵 Audio";
            case "round" -> "⭕ Round video";
            case "video_1080" -> "🎬 1080p";
            case "video_720" -> "🎬 720p";
            case "video_480" -> "🎬 480p";
            case "video_360" -> "🎬 360p";
            default -> "🎬 Video";
        };
    }

    private void deleteMessage(Long chatId, Integer messageId) {
        try {
            DeleteMessage delete = new DeleteMessage();
            delete.setChatId(chatId.toString());
            delete.setMessageId(messageId);
            execute(delete);
        } catch (Exception e) {
            // O'chirib bo'lmasa — ignore
            logger.debug("Could not delete message: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        downloadExecutor.shutdown();
    }

    private String getToken() {
        return botToken;
    }
}
