package uz.chaqmoq.media.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Getter
    public static class DownloadResult {
        private final boolean successful;
        private final File file;
        private final String title;
        private final String mediaType;
        private final String error;

        private DownloadResult(boolean successful, File file, String title, String mediaType, String error) {
            this.successful = successful;
            this.file = file;
            this.title = title;
            this.mediaType = mediaType;
            this.error = error;
        }

        public static DownloadResult success(File file, String title, String mediaType) {
            return new DownloadResult(true, file, title, mediaType, null);
        }

        public static DownloadResult failure(String error) {
            return new DownloadResult(false, null, null, null, error);
        }
    }

    public DownloadResult downloadVideo(String url) {
        return download(url, "video");
    }

    public DownloadResult downloadAudio(String url) {
        return download(url, "audio");
    }

    public DownloadResult downloadAsRoundVideo(String url) {
        DownloadResult result = download(url, "video");
        if (!result.isSuccessful()) return result;

        try {
            File roundFile = convertToRoundVideo(result.getFile());
            cleanupFile(result.getFile());
            return DownloadResult.success(roundFile, result.getTitle(), "round");
        } catch (Exception e) {
            cleanupFile(result.getFile());
            return DownloadResult.failure("Video dumaloq formatga o'girishda xato: " + e.getMessage());
        }
    }

    private DownloadResult download(String url, String type) {
        File folder = createTempFolder();

        try {
            List<String> command = new ArrayList<>();
            command.add(ytdlpPath);

            if ("audio".equals(type)) {
                command.addAll(List.of("-x", "--audio-format", "mp3", "--audio-quality", "0"));
            } else {
                command.addAll(List.of("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"));
            }

            if (url.toLowerCase().contains("tiktok.com")) {
                command.addAll(List.of("--extractor-args",
                        "tiktok:api_hostname=api22-normal-c-alisg.tiktokv.com"));
            }

            command.addAll(List.of(
                    "--max-filesize", maxFileSizeMb + "M",
                    "--no-check-certificates",
                    "-o", folder.getAbsolutePath() + "/%(title)s.%(ext)s",
                    url
            ));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return DownloadResult.failure("Yuklab olish vaqti tugadi (120 sekund)");
            }

            if (process.exitValue() != 0) {
                return DownloadResult.failure(parseError(output.toString()));
            }

            File[] files = folder.listFiles();
            if (files == null || files.length == 0) {
                return DownloadResult.failure("Fayl yuklab olinmadi");
            }

            File downloadedFile = files[0];
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
        File outputFile = new File(inputFile.getParent(), "round_" + System.currentTimeMillis() + ".mp4");

        List<String> command = List.of(
                ffmpegPath,
                "-i", inputFile.getAbsolutePath(),
                "-vf", "crop=min(iw\\,ih):min(iw\\,ih),scale=384:384",
                "-t", "60",
                "-c:v", "libx264",
                "-crf", "28",
                "-c:a", "aac",
                "-b:a", "128k",
                "-y",
                outputFile.getAbsolutePath()
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // consume output
            }
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg vaqti tugadi");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("FFmpeg xato bilan tugadi");
        }

        return outputFile;
    }

    private String parseError(String output) {
        if (output.contains("Video unavailable")) return "Bu video mavjud emas yoki o'chirilgan";
        if (output.contains("Private video")) return "Bu shaxsiy (private) video";
        if (output.contains("Sign in")) return "Bu videoni ko'rish uchun tizimga kirish talab qilinadi";
        if (output.contains("Geo-restricted")) return "Bu video sizning mintaqangizda mavjud emas";
        if (output.contains("age")) return "Bu video yosh chegarasi bilan cheklangan";
        if (output.contains("copyright")) return "Mualliflik huquqi tufayli video mavjud emas";
        if (output.contains("File is larger")) return "Fayl hajmi juda katta (max " + maxFileSizeMb + "MB)";
        if (output.contains("Unsupported URL")) return "Bu URL qo'llab-quvvatlanmaydi";
        if (output.contains("HTTP Error 404")) return "Sahifa topilmadi (404)";
        if (output.contains("HTTP Error 403")) return "Kirish taqiqlangan (403)";
        return "Yuklab olishda xato yuz berdi. Qayta urinib ko'ring.";
    }

    private File createTempFolder() {
        File folder = new File(tempDir, UUID.randomUUID().toString());
        folder.mkdirs();
        return folder;
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
