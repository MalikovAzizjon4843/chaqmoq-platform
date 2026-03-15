package uz.chaqmoq.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.FileBody;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MusicRecognitionService {

    @Value("${acrcloud.access.key:}")
    private String accessKey;

    @Value("${acrcloud.access.secret:}")
    private String accessSecret;

    @Value("${acrcloud.host:identify-eu-west-1.acrcloud.com}")
    private String host;

    @Value("${ffmpeg.path:/usr/bin/ffmpeg}")
    private String ffmpegPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class MusicResult {
        public boolean found;
        public String title;
        public String artist;
        public String album;
        public String releaseDate;
        public String spotifyUrl;
        public String youtubeUrl;

        public String format() {
            if (!found) return null;
            if (title == null || title.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            sb.append("🎵 *").append(escape(title)).append("*\n");
            sb.append("👤 ").append(escape(artist)).append("\n");
            if (album != null && !album.isEmpty()) {
                sb.append("💿 ").append(escape(album)).append("\n");
            }
            if (releaseDate != null && !releaseDate.isEmpty()) {
                sb.append("📅 ").append(releaseDate).append("\n");
            }
            if (spotifyUrl != null && !spotifyUrl.isEmpty()) {
                sb.append("\n[🎧 Spotify](").append(spotifyUrl).append(")");
            }
            if (youtubeUrl != null && !youtubeUrl.isEmpty()) {
                sb.append(" [▶️ YouTube](").append(youtubeUrl).append(")");
            }
            return sb.toString();
        }

        private String escape(String text) {
            if (text == null) return "";
            return text.replace("_", "\\_")
                    .replace("*", "\\*")
                    .replace("[", "\\[")
                    .replace("`", "\\`");
        }
    }

    public MusicResult recognizeFromFile(File inputFile) {
        if (accessKey == null || accessKey.isEmpty()) {
            log.warn("ACRCloud API key not configured");
            MusicResult r = new MusicResult();
            r.found = false;
            return r;
        }

        File audioSample = null;
        try {
            // Har doim audio sample ajrat (video yoki audio bo'lsin)
            log.info("Extracting 15-second audio sample from: {}", inputFile.getName());
            audioSample = extractAudioSample(inputFile);

            long timestamp = System.currentTimeMillis() / 1000;
            String dataType = "audio";
            String signatureVersion = "1";

            // Signature string format (Python dan to'g'ridan-to'g'ri)
            // "POST\n/v1/identify\naccess_key\naudio\n1\ntimestamp"
            String stringToSign = "POST\n/v1/identify\n" + 
                    accessKey + "\n" + 
                    dataType + "\n" + 
                    signatureVersion + "\n" + 
                    timestamp;

            log.info("ACRCloud signature components:");
            log.info("  Host: {}", host);
            log.info("  Timestamp: {}", timestamp);
            log.info("  Sample file: {} ({}KB)", audioSample.getName(), audioSample.length() / 1024);
            log.info("  String to sign:\n{}", stringToSign.replace("\n", "\\n"));

            String signature = hmacSha1(stringToSign, accessSecret);
            log.info("  Signature: {}", signature);

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                String url = "https://" + host + "/v1/identify";
                log.info("POST request to: {}", url);

                HttpPost post = new HttpPost(url);

                MultipartEntityBuilder builder =
                        MultipartEntityBuilder.create();
                builder.addPart("sample",
                        new FileBody(audioSample,
                                ContentType.APPLICATION_OCTET_STREAM,
                                audioSample.getName()));
                builder.addPart("access_key",
                        new StringBody(accessKey, ContentType.TEXT_PLAIN));
                builder.addPart("data_type",
                        new StringBody(dataType, ContentType.TEXT_PLAIN));
                builder.addPart("signature_version",
                        new StringBody(signatureVersion,
                                ContentType.TEXT_PLAIN));
                builder.addPart("signature",
                        new StringBody(signature, ContentType.TEXT_PLAIN));
                builder.addPart("sample_bytes",
                        new StringBody(String.valueOf(audioSample.length()),
                                ContentType.TEXT_PLAIN));
                builder.addPart("timestamp",
                        new StringBody(String.valueOf(timestamp),
                                ContentType.TEXT_PLAIN));

                post.setEntity(builder.build());

                String response = client.execute(post, httpResponse ->
                        new String(httpResponse.getEntity()
                                .getContent().readAllBytes())
                );

                log.info("ACRCloud response: {}", response);
                return parseResponse(response);
            }
        } catch (Exception e) {
            log.error("ACRCloud recognition failed", e);
            MusicResult r = new MusicResult();
            r.found = false;
            return r;
        } finally {
            // Temp audio sample faylni o'chir
            if (audioSample != null && audioSample.exists()) {
                if (audioSample.delete()) {
                    log.debug("Temporary audio sample deleted: {}", audioSample.getName());
                } else {
                    log.warn("Failed to delete temporary audio sample: {}", audioSample.getAbsolutePath());
                }
            }
        }
    }

    private File extractAudioSample(File inputFile) throws Exception {
        String outputPath = inputFile.getParent() + 
            java.io.File.separator + "sample_" + System.currentTimeMillis() + ".mp3";
        
        List<String> cmd = new java.util.ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputFile.getAbsolutePath(),
            "-t", "15",        // faqat 15 sekund
            "-vn",             // video track yo'q
            "-ar", "44100",    // sample rate
            "-ac", "2",        // stereo
            "-b:a", "128k",    // bitrate - kichik fayl uchun
            "-f", "mp3",
            "-y",
            outputPath
        ));
        
        log.info("Extracting 15-second audio sample: {}", String.join(" ", cmd));
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.error("FFmpeg audio extraction timeout");
            throw new Exception("FFmpeg audio extraction timeout");
        }
        
        if (process.exitValue() != 0) {
            log.error("FFmpeg extraction failed with exit code: {}", process.exitValue());
            throw new Exception("FFmpeg extraction failed with exit code: " + process.exitValue());
        }
        
        File sampleFile = new File(outputPath);
        if (!sampleFile.exists() || sampleFile.length() == 0) {
            log.error("Audio sample extraction produced no output");
            throw new Exception("Audio sample extraction failed - empty output");
        }
        
        log.info("Audio sample extracted successfully: {} ({}KB)", 
            sampleFile.getName(), 
            sampleFile.length() / 1024);
        
        return sampleFile;
    }

    private String hmacSha1(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        SecretKeySpec keySpec = new SecretKeySpec(
                key.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(
                data.getBytes(StandardCharsets.UTF_8));
        // Base64 encode — java.util.Base64 ishlatamiz
        return java.util.Base64.getEncoder().encodeToString(rawHmac);
    }

    private MusicResult parseResponse(String json) {
        MusicResult result = new MusicResult();
        try {
            JsonNode root = objectMapper.readTree(json);
            int statusCode = root.path("status").path("code").asInt();

            if (statusCode != 0) {
                result.found = false;
                return result;
            }

            JsonNode music = root.path("metadata")
                    .path("music");
            if (!music.isArray() || music.isEmpty()) {
                result.found = false;
                return result;
            }

            JsonNode song = music.get(0);
            result.found = true;
            result.title = song.path("title").asText();

            JsonNode artists = song.path("artists");
            if (artists.isArray() && !artists.isEmpty()) {
                result.artist = artists.get(0).path("name").asText();
            }

            result.album = song.path("album").path("name").asText();
            result.releaseDate = song.path("release_date").asText();

            // Spotify URL
            JsonNode spotify = song.path("external_metadata")
                    .path("spotify");
            if (!spotify.isMissingNode()) {
                String trackId = spotify.path("track")
                        .path("id").asText();
                if (!trackId.isEmpty()) {
                    result.spotifyUrl =
                            "https://open.spotify.com/track/" + trackId;
                }
            }

            // YouTube URL
            JsonNode youtube = song.path("external_metadata")
                    .path("youtube");
            if (!youtube.isMissingNode()) {
                String videoId = youtube.path("vid").asText();
                if (!videoId.isEmpty()) {
                    result.youtubeUrl =
                            "https://www.youtube.com/watch?v=" + videoId;
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse ACRCloud response", e);
            result.found = false;
        }
        return result;
    }

    // ACRCloud text search yo'q — foydalanuvchi matn yuborganda
    // keyinroq YouTube search orqali qidirish qo'shiladi
    @SuppressWarnings("unused")
    public MusicResult searchByText(String query) {
        // Hozircha oddiy javob — keyinroq YouTube search qo'shiladi
        MusicResult result = new MusicResult();
        result.found = false;
        return result;
    }
}
