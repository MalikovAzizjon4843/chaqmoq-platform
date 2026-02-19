package uz.chaqmoq.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
public class MusicRecognitionService {

    private static final String AUDD_API_URL = "https://api.audd.io/";

    @Value("${audd.api.key:}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Getter
    public static class MusicResult {
        private final boolean found;
        private final String title;
        private final String artist;
        private final String album;
        private final String releaseDate;
        private final String spotifyUrl;
        private final String appleUrl;

        public MusicResult(boolean found, String title, String artist, String album,
                           String releaseDate, String spotifyUrl, String appleUrl) {
            this.found = found;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.releaseDate = releaseDate;
            this.spotifyUrl = spotifyUrl;
            this.appleUrl = appleUrl;
        }

        public String format() {
            if (!found) return "🎵 Musiqa topilmadi. Boshqa audio bilan urinib ko'ring.";

            StringBuilder sb = new StringBuilder();
            sb.append("🎵 *Musiqa topildi!*\n\n");
            sb.append("🎤 *Ijrochi:* ").append(escapeMarkdown(artist)).append("\n");
            sb.append("🎶 *Nomi:* ").append(escapeMarkdown(title)).append("\n");
            if (album != null && !album.isBlank()) {
                sb.append("💿 *Albom:* ").append(escapeMarkdown(album)).append("\n");
            }
            if (releaseDate != null && !releaseDate.isBlank()) {
                sb.append("📅 *Sana:* ").append(escapeMarkdown(releaseDate)).append("\n");
            }
            sb.append("\n");
            if (spotifyUrl != null && !spotifyUrl.isBlank()) {
                sb.append("🟢 [Spotify'da tinglash](").append(spotifyUrl).append(")\n");
            }
            if (appleUrl != null && !appleUrl.isBlank()) {
                sb.append("🍎 [Apple Music'da tinglash](").append(appleUrl).append(")\n");
            }
            return sb.toString();
        }

        private String escapeMarkdown(String text) {
            if (text == null) return "";
            return text.replace("_", "\\_")
                    .replace("*", "\\*")
                    .replace("[", "\\[")
                    .replace("`", "\\`");
        }
    }

    public MusicResult recognizeFromFile(File audioFile) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(AUDD_API_URL);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("api_token", apiKey);
            builder.addTextBody("return", "apple_music,spotify");
            builder.addBinaryBody("file", audioFile, ContentType.DEFAULT_BINARY, audioFile.getName());

            post.setEntity(builder.build());

            return httpClient.execute(post, response -> {
                String json = EntityUtils.toString(response.getEntity());
                return parseResponse(json);
            });
        } catch (Exception e) {
            log.error("Music recognition error: {}", e.getMessage(), e);
            return new MusicResult(false, null, null, null, null, null, null);
        }
    }

    public MusicResult recognizeFromUrl(String audioUrl) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(AUDD_API_URL);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("api_token", apiKey);
            builder.addTextBody("url", audioUrl);
            builder.addTextBody("return", "apple_music,spotify");

            post.setEntity(builder.build());

            return httpClient.execute(post, response -> {
                String json = EntityUtils.toString(response.getEntity());
                return parseResponse(json);
            });
        } catch (Exception e) {
            log.error("Music recognition error: {}", e.getMessage(), e);
            return new MusicResult(false, null, null, null, null, null, null);
        }
    }

    private MusicResult parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            if (!"success".equals(root.path("status").asText())) {
                return new MusicResult(false, null, null, null, null, null, null);
            }

            JsonNode result = root.path("result");
            if (result.isNull() || result.isMissingNode()) {
                return new MusicResult(false, null, null, null, null, null, null);
            }

            String title = result.path("title").asText(null);
            String artist = result.path("artist").asText(null);
            String album = result.path("album").asText(null);
            String releaseDate = result.path("release_date").asText(null);

            String spotifyUrl = null;
            JsonNode spotify = result.path("spotify");
            if (!spotify.isNull() && !spotify.isMissingNode()) {
                JsonNode externalUrls = spotify.path("external_urls");
                if (!externalUrls.isMissingNode()) {
                    spotifyUrl = externalUrls.path("spotify").asText(null);
                }
            }

            String appleUrl = null;
            JsonNode appleMusic = result.path("apple_music");
            if (!appleMusic.isNull() && !appleMusic.isMissingNode()) {
                appleUrl = appleMusic.path("url").asText(null);
            }

            return new MusicResult(title != null, title, artist, album, releaseDate, spotifyUrl, appleUrl);
        } catch (Exception e) {
            log.error("Parse response error: {}", e.getMessage(), e);
            return new MusicResult(false, null, null, null, null, null, null);
        }
    }
}
