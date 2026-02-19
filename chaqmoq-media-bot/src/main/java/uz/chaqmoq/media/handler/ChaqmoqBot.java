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

        if (message.hasAudio() || message.hasVoice()) {
            handleMusicRecognition(message);
            return;
        }

        if (message.hasText()) {
            String text = message.getText().trim();

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
                        sendText(chatId, "Noto'g'ri buyruq. /help ni bosing.");
                    }
                }
            }
        }
    }

    private void handleDownloadRequest(Long chatId, String url) {
        PlatformDetector.Platform platform = platformDetector.detect(url);

        if (platform == PlatformDetector.Platform.UNKNOWN) {
            sendText(chatId, "❌ Bu platforma qo'llab-quvvatlanmaydi.\n\n" +
                    "Qo'llab-quvvatlanadigan platformalar:\n" +
                    "YouTube, TikTok, Instagram, Facebook, Twitter/X, Snapchat, Pinterest, Threads, Likee");
            return;
        }

        sendDownloadOptions(chatId, url, platform);
    }

    private void sendDownloadOptions(Long chatId, String url, PlatformDetector.Platform platform) {
        String videoCallback = urlCacheService.createCallbackData("dl_video", url);
        String audioCallback = urlCacheService.createCallbackData("dl_audio", url);
        String roundCallback = urlCacheService.createCallbackData("dl_round", url);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder()
                .text("📥 Video")
                .callbackData(videoCallback)
                .build());
        row1.add(InlineKeyboardButton.builder()
                .text("🎵 Audio MP3")
                .callbackData(audioCallback)
                .build());
        rows.add(row1);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(InlineKeyboardButton.builder()
                .text("⭕ Dumaloq Video")
                .callbackData(roundCallback)
                .build());
        rows.add(row2);

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
            case "dl_audio" -> processDownload(chatId, messageId, userId, url, "audio");
            case "dl_round" -> processDownload(chatId, messageId, userId, url, "round");
        }
    }

    private void processDownload(Long chatId, Integer messageId, Long userId, String url, String type) {
        PlatformDetector.Platform platform = platformDetector.detect(url);
        String typeName = switch (type) {
            case "audio" -> "audio (MP3)";
            case "round" -> "dumaloq video";
            default -> "video";
        };

        editMessage(chatId, messageId, "⏳ " + platform.getEmoji() + " " + typeName + " yuklanmoqda...\n\nBiroz kuting...");

        MediaDownloadService.DownloadResult result = null;
        try {
            result = switch (type) {
                case "audio" -> mediaDownloadService.downloadAudio(url);
                case "round" -> mediaDownloadService.downloadAsRoundVideo(url);
                default -> mediaDownloadService.downloadVideo(url);
            };

            if (result.isSuccessful()) {
                sendMediaFile(chatId, result.getFile(), result.getTitle(), type);
                userService.recordDownload(userId, url, platform.getName(), type, true, null);
                editMessage(chatId, messageId, "✅ " + platform.getEmoji() + " Muvaffaqiyatli yuklandi!");
            } else {
                editMessage(chatId, messageId, "❌ " + result.getError());
                userService.recordDownload(userId, url, platform.getName(), type, false, result.getError());
            }
        } catch (Exception e) {
            logger.error("Download processing error: {}", e.getMessage(), e);
            editMessage(chatId, messageId, "❌ Xatolik yuz berdi: " + e.getMessage());
            userService.recordDownload(userId, url, platform.getName(), type, false, e.getMessage());
        } finally {
            if (result != null && result.getFile() != null) {
                mediaDownloadService.cleanupFile(result.getFile());
            }
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
                SendVideo sendVideo = SendVideo.builder()
                        .chatId(chatId.toString())
                        .video(inputFile)
                        .caption("⚡ @" + botUsername)
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
            SendMessage msg = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(result.format())
                    .parseMode("Markdown")
                    .disableWebPagePreview(true)
                    .build();
            execute(msg);

            mediaDownloadService.cleanupFile(audioFile);
        } catch (Exception e) {
            logger.error("Music recognition error: {}", e.getMessage(), e);
            sendText(chatId, "❌ Musiqa aniqlashda xato yuz berdi.");
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
        String text = "👋 Salom, *" + escapeMarkdown(name) + "*!\n\n" +
                "Men *Chaqmoq Bot* — tezkor media yuklovchi! ⚡\n\n" +
                "🎬 *Imkoniyatlarim:*\n" +
                "├ 📥 Video yuklab olish (YouTube, TikTok, Instagram...)\n" +
                "├ 🎵 Audio MP3 formatda yuklab olish\n" +
                "├ ⭕ Dumaloq video (video xabar) formatda yuklab olish\n" +
                "└ 🎶 Musiqa aniqlash (Shazam)\n\n" +
                "📌 *Foydalanish:*\n" +
                "Menga video havolasini yuboring yoki audio yuboring!\n\n" +
                "Quyidagi tugmalardan foydalaning 👇";

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(getMainKeyboard())
                .build();

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
                "📘 Facebook  🐦 Twitter/X  👻 Snapchat\n" +
                "📌 Pinterest  🧵 Threads  ❤️ Likee\n\n" +
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
