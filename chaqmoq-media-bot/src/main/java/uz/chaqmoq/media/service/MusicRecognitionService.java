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

@Service
@Slf4j
public class MusicRecognitionService {

    @Value("${acrcloud.access.key:}")
    private String accessKey;

    @Value("${acrcloud.access.secret:}")
    private String accessSecret;

    @Value("${acrcloud.host:identify-eu-west-1.acrcloud.com}")
    private String host;

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

    public MusicResult recognizeFromFile(File audioFile) {
        if (accessKey == null || accessKey.isEmpty()) {
            log.warn("ACRCloud API key not configured");
            MusicResult r = new MusicResult();
            r.found = false;
            return r;
        }

        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String dataType = "audio";
            String signatureVersion = "1";

            // Signature string to'g'ri format:
            // "HTTP_METHOD\nHTTP_URI\nACCESS_KEY\nDATA_TYPE\nSIG_VERSION\nTIMESTAMP"
            String stringToSign = "POST" + "\n" +
                    "/v1/identify" + "\n" +
                    accessKey + "\n" +
                    dataType + "\n" +
                    signatureVersion + "\n" +
                    timestamp;

            log.info("String to sign: [{}]", stringToSign);
            log.info("Access key: [{}]", accessKey);
            log.info("Host: [{}]", host);

            String signature = hmacSha1(stringToSign, accessSecret);
            log.info("Signature: [{}]", signature);

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                String url = "https://" + host + "/v1/identify";
                log.info("ACRCloud URL: {}", url);

                HttpPost post = new HttpPost(url);

                MultipartEntityBuilder builder =
                        MultipartEntityBuilder.create();
                builder.addPart("sample",
                        new FileBody(audioFile,
                                ContentType.APPLICATION_OCTET_STREAM,
                                audioFile.getName()));
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
                        new StringBody(String.valueOf(audioFile.length()),
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
        }
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
            if (!music.isArray() || music.size() == 0) {
                result.found = false;
                return result;
            }

            JsonNode song = music.get(0);
            result.found = true;
            result.title = song.path("title").asText();

            JsonNode artists = song.path("artists");
            if (artists.isArray() && artists.size() > 0) {
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
    public MusicResult searchByText(String query) {
        // Hozircha oddiy javob — keyinroq YouTube search qo'shiladi
        MusicResult result = new MusicResult();
        result.found = false;
        return result;
    }
}
