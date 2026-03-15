package uz.chaqmoq.media.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.chaqmoq.media.util.PlatformDetector;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MediaDownloadService {

    @Value("${ytdlp.path:/usr/local/bin/yt-dlp}")
    private String ytdlpPath;

    @Value("${ffmpeg.path:/usr/bin/ffmpeg}")
    private String ffmpegPath;

    @Value("${app.temp.dir:/tmp/chaqmoq}")
    private String tempDir;

    @Value("${app.max.filesize.mb:50}")
    private int maxFileSizeMb;

    @Value("${ytdlp.cookies.path:}")
    private String cookiesPath;

    private String currentDownloadType;

    private final PlatformDetector platformDetector;
    private final CobaltDownloadService cobaltDownloadService;

    public MediaDownloadService(PlatformDetector platformDetector, CobaltDownloadService cobaltDownloadService) {
        this.platformDetector = platformDetector;
        this.cobaltDownloadService = cobaltDownloadService;
    }

    @Getter
    public static class DownloadResult {
        private final boolean successful;
        private final File file;
        private final String title;
        private final String mediaType;
        private final String notice;
        private final String error;

        private DownloadResult(boolean successful, File file, String title, String mediaType, String notice, String error) {
            this.successful = successful;
            this.file = file;
            this.title = title;
            this.mediaType = mediaType;
            this.notice = notice;
            this.error = error;
        }

        public static DownloadResult success(File file, String title, String mediaType) {
            return new DownloadResult(true, file, title, mediaType, null, null);
        }

        public static DownloadResult success(File file, String title, String mediaType, String notice) {
            return new DownloadResult(true, file, title, mediaType, notice, null);
        }

        public static DownloadResult failure(String error) {
            return new DownloadResult(false, null, null, null, null, error);
        }
    }

    public DownloadResult downloadVideo(String url) {
        return downloadVideo(url, "video");
    }

    public DownloadResult downloadVideo(String url, String type) {
        // Boshqa platformalar uchun split and download
        return splitAndDownload(url, type).stream()
            .findFirst()
            .orElse(DownloadResult.failure("Video yuklab olinmadi"));
    }

    public DownloadResult downloadAudio(String url) {
        if (platformDetector.detect(url) == PlatformDetector.Platform.INSTAGRAM) {
            DownloadResult videoResult = download(url, "video");
            if (!videoResult.isSuccessful()) return videoResult;
            return DownloadResult.success(
                    videoResult.getFile(),
                    videoResult.getTitle(),
                    "video",
                    "Instagram audio formatini qo'llab-quvvatlamaydi, video yuklandi"
            );
        }
        return download(url, "audio");
    }

    public DownloadResult downloadAsRoundVideo(String url) {
        // 1. Avval oddiy video yuklash (yt-dlp)
        DownloadResult raw = download(url, "video_720");
        if (!raw.isSuccessful()) return raw;
        
        // 2. FFmpeg bilan crop + compress (384x384, max 11MB)
        try {
            File roundFile = convertToRoundVideo(raw.getFile());
            if (raw.getFile().delete()) {
                log.debug("Original video file deleted after round conversion");
            }
            return DownloadResult.success(roundFile, raw.getTitle(), "round");
        } catch (Exception e) {
            log.error("Round video conversion failed", e);
            return DownloadResult.failure("Round video yaratishda xatolik");
        }
    }

    public List<DownloadResult> splitAndDownload(String url, String type) {
        // 1. Avval to'liq yuklash
        DownloadResult result = download(url, type);
        if (!result.isSuccessful()) return List.of(result);

        long fileSizeBytes = result.getFile().length();
        long maxBytes = 1024L * 1024 * 1024; // 1GB

        // Fayl 1GB dan kichik bo'lsa bo'lish shart emas
        if (fileSizeBytes <= maxBytes) {
            return List.of(result);
        }

        // FFmpeg bilan qismlarga bo'lish
        return splitVideo(result.getFile(), maxBytes);
    }

    private List<DownloadResult> splitVideo(File inputFile, long maxBytes) {
        List<DownloadResult> parts = new ArrayList<>();
        try {
            // Video davomiyligini olish
            long durationSecs = getVideoDuration(inputFile);
            long fileSizeBytes = inputFile.length();

            // Telegram Bot API 50MB limit — shuning uchun 40MB target
            long targetPartBytes = 40L * 1024 * 1024; // 40MB
            int partCount = (int) Math.ceil((double) fileSizeBytes / targetPartBytes);
            long partDuration = durationSecs / partCount;

            // Original fayl nomini olish
            String originalName = inputFile.getName();
            int dotIndex = originalName.lastIndexOf('.');
            String baseName = (dotIndex > 0) ? originalName.substring(0, dotIndex) : originalName;

            for (int i = 0; i < partCount; i++) {
                long startTime = i * partDuration;
                String outputPath = inputFile.getParent() +
                        File.separator + "part_" + String.format("%02d", i+1) +
                        "_of_" + String.format("%02d", partCount) + ".mp4";

                List<String> cmd = new ArrayList<>(List.of(
                        ffmpegPath,
                        "-i", inputFile.getAbsolutePath(),
                        "-ss", String.valueOf(startTime),
                        "-t", String.valueOf(partDuration),
                        "-c", "copy",
                        "-y",
                        outputPath
                ));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.waitFor(120, TimeUnit.SECONDS);

                File partFile = new File(outputPath);
                if (partFile.exists() && partFile.length() > 0) {
                    parts.add(DownloadResult.success(
                            partFile,
                            "📦 " + (i+1) + "/" + partCount + " qism — " + baseName,
                            "video"
                    ));
                }
            }

            // Asl faylni o'chirish
            if (inputFile.delete()) {
                log.debug("Original file deleted after splitting");
            }

        } catch (Exception e) {
            log.error("Video splitting failed", e);
            parts.add(DownloadResult.failure("Videoni bo'lishda xatolik: " + e.getMessage()));
        }
        return parts;
    }

    private long getVideoDuration(File videoFile) throws Exception {
        List<String> cmd = List.of(
                ffmpegPath, "-i", videoFile.getAbsolutePath()
        );
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] output = process.getInputStream().readAllBytes();
        process.waitFor(30, TimeUnit.SECONDS);

        // Duration ni parse qilish: "Duration: HH:MM:SS.ms"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Duration: (\\d+):(\\d+):(\\d+)")
                .matcher(new String(output));
        if (m.find()) {
            return Long.parseLong(m.group(1)) * 3600 +
                    Long.parseLong(m.group(2)) * 60 +
                    Long.parseLong(m.group(3));
        }
        return 0;
    }

    private DownloadResult download(String url, String type) {
        if (url == null || url.isEmpty()) {
            return DownloadResult.failure("Havola bo'sh");
        }
        
        this.currentDownloadType = type;
        File folder = createTempFolder();

        try {
            String lowerUrl = url.toLowerCase();
            boolean isTikTok = lowerUrl.contains("tiktok.com");

            List<String> command = new ArrayList<>();
            command.add(ytdlpPath);
            command.add("--ffmpeg-location");
            command.add(ffmpegPath);

            if ("audio".equals(type)) {
                command.addAll(List.of("-x", "--audio-format", "mp3", "--audio-quality", "0"));
            } else {
                // Sifatni type dan olish
                String height = "720"; // default
                if (type.startsWith("video_")) {
                    height = type.replace("video_", "");
                }

                // Format selector
                String formatSelector = String.format(
                    "bestvideo[ext=mp4][height<=%s]+bestaudio[ext=m4a]/" +
                    "bestvideo[height<=%s]+bestaudio/best[height<=%s]/best",
                    height, height, height
                );
                command.add("-f");
                command.add(formatSelector);
                command.add("--merge-output-format");
                command.add("mp4");
            }

            if (isTikTok) {
                // TikTok uchun impersonation (Chrome kabi ko'rinamiz)
                command.add("--impersonate");
                command.add("Chrome-133");

                // Timeout oshirish
                command.add("--socket-timeout");
                command.add("90");
            }

            if (lowerUrl.contains("snapchat.com")) {
                command.add("--add-header");
                command.add("User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15");
                command.add("--add-header");
                command.add("Accept-Language:en-US,en;q=0.9");
                // Story va Spotlight URL larini qo'llab-quvvatlash uchun
                command.add("--extractor-args");
                command.add("snapchat:stories=yes");
            }

            if (lowerUrl.contains("likee.video")) {
                // Short linkni oldin resolve qil
                command.add("--no-playlist");
                command.add("--extractor-args");
                command.add("likee:app_name=likee");
            }

            // Cookies qo'shish (barcha platformalar uchun)
            if (cookiesPath != null && !cookiesPath.isEmpty()) {
                File cookiesFile = new File(cookiesPath);
                if (cookiesFile.exists()) {
                    command.add("--cookies");
                    command.add(cookiesPath);
                }
            }


            command.addAll(List.of(
                    "--max-filesize", "50M",
                    "--no-check-certificates",
                    "-o", folder.getAbsolutePath() + "/%(title)s.%(ext)s",
                    url
            ));

            ProcessResult result = runProcess(command, url);
            if (!result.finished) {
                log.error("yt-dlp timeout. Output so far:\n{}", result.output);
                return DownloadResult.failure(
                    "Yuklab olish vaqti tugadi. Video juda uzun yoki katta bo'lishi mumkin (max 45MB)"
                );
            }

            // Agar --impersonate chrome ishlamasa (yt-dlp versiyasiga bog'liq), fallback: User-Agent + Referer
            if (isTikTok && result.exitCode != 0) {
                String outLower = (result.output == null ? "" : result.output.toLowerCase());
                if (outLower.contains("impersonation")
                        || outLower.contains("--impersonate")
                        || outLower.contains("unknown option")) {
                    List<String> fallbackCommand = new ArrayList<>();
                    for (int i = 0; i < command.size(); i++) {
                        String c = command.get(i);
                        if ("--impersonate".equals(c)) {
                            i++; // skip value (e.g. chrome)
                            continue;
                        }
                        fallbackCommand.add(c);
                    }
                    fallbackCommand.add("--add-header");
                    fallbackCommand.add("User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
                    fallbackCommand.add("--add-header");
                    fallbackCommand.add("Referer:https://www.tiktok.com/");

                    log.info("yt-dlp TikTok fallback (UA/Referer) command: {}", String.join(" ", fallbackCommand));
                    result = runProcess(fallbackCommand, url);
                    if (!result.finished) {
                        log.error("yt-dlp timeout on TikTok fallback. Output so far:\n{}", result.output);
                        return DownloadResult.failure(
                            "Yuklab olish vaqti tugadi. Video juda uzun yoki katta bo'lishi mumkin (max 45MB)"
                        );
                    }
                }
            }

            // Fayl to'liq yozilishini kutish (max 5 sekund)
            int waitCount = 0;
            while (waitCount < 10) {
                File[] currentFiles = folder.listFiles();
                if (currentFiles != null) {
                    File[] readyFiles = Arrays.stream(currentFiles)
                            .filter(f -> !f.getName().endsWith(".part")
                                    && !f.getName().endsWith(".ytdl"))
                            .toArray(File[]::new);
                    if (readyFiles.length > 0) break;
                }
                Thread.sleep(500);
                waitCount++;
            }

            if (result.exitCode != 0) {
                log.error("yt-dlp exited with code {}. Output:\n{}", result.exitCode, result.output);
                return DownloadResult.failure(parseError(result.output));
            } else {
                String out = (result.output == null ? "" : result.output.trim());
                if (!out.isEmpty()) {
                    log.debug("yt-dlp output:\n{}", out);
                }
            }

            File[] files = folder.listFiles();
            if (files == null || files.length == 0) {
                return DownloadResult.failure("Fayl yuklab olinmadi");
            }

            File downloadedFile = Arrays.stream(files)
                    .filter(f -> !f.getName().endsWith(".part")
                            && !f.getName().endsWith(".ytdl"))
                    .filter(f -> f.getName().endsWith(".mp4"))
                    .findFirst()
                    .orElseGet(() -> Arrays.stream(files)
                            .filter(f -> !f.getName().endsWith(".part")
                                    && !f.getName().endsWith(".ytdl")
                                    && !f.getName().endsWith(".m4a")
                                    && !f.getName().endsWith(".webm"))
                            .findFirst()
                            .orElse(null));

            if (downloadedFile == null) {
                return DownloadResult.failure("Fayl yuklab olinmadi yoki hali tugallanmadi");
            }

            log.info("Yuklangan fayl: {}, hajmi: {} MB",
                    downloadedFile.getName(),
                    downloadedFile.length() / 1024 / 1024);
            String title = downloadedFile.getName();
            int dotIndex = title.lastIndexOf('.');
            if (dotIndex > 0) {
                title = title.substring(0, dotIndex);
            }

            return DownloadResult.success(downloadedFile, title, type);

        } catch (Exception e) {
            log.error("Download error: {}", e.getMessage(), e);
            return DownloadResult.failure("Yuklab olishda xato: " + e.getMessage());
        }
    }

    private File convertToRoundVideo(File inputFile) throws Exception {
        String outputPath = inputFile.getParent() + 
            File.separator + "round_" + System.currentTimeMillis() + ".mp4";
        
        List<String> cmd = new ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputFile.getAbsolutePath(),
            "-t", "60",
            "-vf", "crop=min(iw\\,ih):min(iw\\,ih),scale=384:384",
            "-c:v", "libx264",
            "-crf", "35",
            "-preset", "fast",
            "-c:a", "aac",
            "-b:a", "48k",
            "-b:v", "150k",
            "-maxrate", "150k",
            "-bufsize", "300k",
            "-movflags", "+faststart",
            "-y",
            outputPath
        ));
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("FFmpeg timeout");
        }
        
        File roundFile = new File(outputPath);
        log.info("Round video converted: {}MB → {}MB",
            inputFile.length() / 1024 / 1024,
            roundFile.length() / 1024 / 1024);
        
        // Hali ham 11MB dan katta bo'lsa qayta compress
        long maxBytes = 11L * 1024 * 1024;
        if (roundFile.length() > maxBytes) {
            log.info("Round video still too large ({}MB), recompressing with crf 40...",
                roundFile.length() / 1024 / 1024);
                
            String outputPath2 = inputFile.getParent() + 
                File.separator + "round2_" + System.currentTimeMillis() + ".mp4";
            List<String> cmd2 = new ArrayList<>(List.of(
                ffmpegPath,
                "-i", roundFile.getAbsolutePath(),
                "-c:v", "libx264",
                "-crf", "40",
                "-preset", "fast",
                "-c:a", "aac",
                "-b:a", "32k",
                "-b:v", "100k",
                "-maxrate", "100k",
                "-bufsize", "200k",
                "-y",
                outputPath2
            ));
            ProcessBuilder pb2 = new ProcessBuilder(cmd2);
            pb2.redirectErrorStream(true);
            Process p2 = pb2.start();
            p2.waitFor(120, TimeUnit.SECONDS);
            
            if (p2.exitValue() == 0) {
                if (roundFile.delete()) {
                    log.debug("Original round video deleted after recompression");
                }
                roundFile = new File(outputPath2);
                log.info("Round video recompressed: {}MB",
                    roundFile.length() / 1024 / 1024);
            } else {
                log.warn("Recompression failed, using original");
            }
        }
        
        return roundFile;
    }

    private String parseError(String output) {
        String o = output == null ? "" : output.toLowerCase();

        if (o.contains("video unavailable")) return "Bu video mavjud emas yoki o'chirilgan";
        if (o.contains("private video")) return "Bu shaxsiy (private) video";
        if (o.contains("sign in")) return "YouTube tizimga kirish talab qiladi. Boshqa video yuboring";
        if (o.contains("cookies")) return "Bu video yuklab olish uchun login kerak";
        if (o.contains("confirm your age")) return "Yoshga oid cheklov bor, yuklab bo'lmadi";
        if (o.contains("geo-restricted")) return "Bu video sizning mintaqangizda mavjud emas";
        if (o.contains("age-restricted") || o.contains("age restricted") || o.contains("age")) return "Bu video yosh chegarasi bilan cheklangan";
        if (o.contains("copyright")) return "Mualliflik huquqi tufayli video mavjud emas";
        if (o.contains("file is larger than max-filesize")) {
            String hint = "";
            if (currentDownloadType != null) {
                if (currentDownloadType.equals("video_1080")) {
                    hint = "720p yoki 480p ni sinab ko'ring";
                } else if (currentDownloadType.equals("video_720")) {
                    hint = "480p yoki 360p ni sinab ko'ring";
                } else if (currentDownloadType.equals("video_480")) {
                    hint = "360p ni sinab ko'ring";
                } else if (currentDownloadType.equals("video_360")) {
                    hint = "Video juda uzun. Audio sifatida yuklab oling";
                } else {
                    hint = "Pastroq sifat tanlang";
                }
            } else {
                hint = "Pastroq sifat tanlang";
            }
            return "⚠️ Video hajmi 50MB dan katta.\n\n💡 " + hint;
        }
        if (o.contains("file is larger")) return "Fayl hajmi juda katta (max " + maxFileSizeMb + "MB)";
        if (o.contains("unsupported url")) return "Bu turdagi havola qo'llab-quvvatlanmaydi. Boshqa havola yuboring";
        if (o.contains("timed out") || o.contains("timeout")) return "Ulanish vaqti tugadi. Qayta urinib ko'ring";
        if (o.contains("impersonation")) return "TikTok hozir band. Bir oz kutib qayta urinib ko'ring";
        if (o.contains("http error 404")) return "Sahifa topilmadi (404)";
        if (o.contains("http error 403")) return "Kirish taqiqlangan (403)";
        return "Yuklab olishda xato yuz berdi. Qayta urinib ko'ring.";
    }

    private static class ProcessResult {
        private final boolean finished;
        private final int exitCode;
        private final String output;

        private ProcessResult(boolean finished, int exitCode, String output) {
            this.finished = finished;
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private ProcessResult runProcess(List<String> command, String url) throws IOException, InterruptedException {
        log.info("yt-dlp command: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                log.warn("Failed to read yt-dlp output: {}", e.getMessage());
            }
        }, "ytdlp-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        // YouTube uchun 600 sekund (10 daqiqa), boshqalar 180 sekund
        long timeout = 180;
        if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
            timeout = 600;
        }

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(false, -1, output.toString());
        }

        try {
            outputReader.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        return new ProcessResult(true, process.exitValue(), output.toString());
    }

    private File createTempFolder() {
        File tempBase = new File(tempDir);
        if (!tempBase.exists()) {
            if (tempBase.mkdirs()) {
                log.debug("Temp base directory created: {}", tempDir);
            }
        }

        File folder = new File(tempBase, UUID.randomUUID().toString());
        if (folder.mkdirs()) {
            log.debug("Download folder created: {}", folder.getAbsolutePath());
        }
        return folder;
    }

    public File compressRoundVideo(File inputFile) throws Exception {
        String outputPath = inputFile.getParent() + 
            File.separator + "compressed_" + System.currentTimeMillis() + ".mp4";
        
        List<String> cmd = List.of(
            ffmpegPath,
            "-i", inputFile.getAbsolutePath(),
            "-c:v", "libx264",
            "-crf", "40",
            "-preset", "fast",
            "-c:a", "aac",
            "-b:a", "32k",
            "-b:v", "100k",
            "-maxrate", "100k",
            "-bufsize", "200k",
            "-y",
            outputPath
        );
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor(120, TimeUnit.SECONDS);
        
        File compressed = new File(outputPath);
        log.info("Compressed round video: {}MB → {}MB",
            inputFile.length() / 1024 / 1024,
            compressed.length() / 1024 / 1024);
        
        return compressed;
    }

    public void cleanupFile(File file) {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            Files.deleteIfExists(file.toPath());
            if (parent != null && parent.getAbsolutePath().startsWith(tempDir)) {
                File[] remaining = parent.listFiles();
                if (remaining == null || remaining.length == 0) {
                    Files.deleteIfExists(parent.toPath());
                }
            }
        } catch (IOException e) {
            log.warn("Cleanup error: {}", e.getMessage());
        }
    }
}
