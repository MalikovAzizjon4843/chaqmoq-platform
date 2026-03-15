package uz.chaqmoq.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

@Service
@Slf4j
public class CobaltDownloadService {

    @Value("${cobalt.api.url:http://localhost:9000}")
    private String cobaltApiUrl;

    @Value("${app.temp.dir:/tmp/chaqmoq}")
    private String tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cobalt orqali video yuklab olish
    // URL qaytaradi (redirect link)
    public String getDownloadUrl(String url) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(cobaltApiUrl + "/");
            String body = "{\"url\": \"" + url + "\"}";
            post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Accept", "application/json");

            String response = client.execute(post, httpResponse ->
                new String(httpResponse.getEntity().getContent().readAllBytes())
            );

            log.info("Cobalt response: {}", response);
            JsonNode root = objectMapper.readTree(response);
            String status = root.path("status").asText();

            if (status.equals("tunnel") || status.equals("redirect")) {
                return root.path("url").asText();
            } else if (status.equals("picker")) {
                // Bir nechta media bo'lsa — birinchisini ol
                JsonNode items = root.path("picker");
                if (items.isArray() && items.size() > 0) {
                    return items.get(0).path("url").asText();
                }
            }
            log.warn("Cobalt unsupported status: {}", status);
            return null;
        } catch (Exception e) {
            log.error("Cobalt getDownloadUrl failed", e);
            return null;
        }
    }

    // URL dan faylni yuklab olish
    public File downloadFromUrl(String downloadUrl, String filename) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(downloadUrl);
            return client.execute(get, httpResponse -> {
                File dir = new File(tempDir + java.io.File.separator + UUID.randomUUID());
                if (dir.mkdirs()) {
                    log.debug("Temp directory created: {}", dir.getAbsolutePath());
                }
                File file = new File(dir, filename + ".mp4");
                try (InputStream in = httpResponse.getEntity().getContent();
                     FileOutputStream out = new FileOutputStream(file)) {
                    in.transferTo(out);
                }
                return file;
            });
        } catch (Exception e) {
            log.error("Cobalt downloadFromUrl failed", e);
            return null;
        }
    }

    // To'liq: URL → File
    public MediaDownloadService.DownloadResult download(String url) {
        try {
            String downloadUrl = getDownloadUrl(url);
            if (downloadUrl == null) {
                return MediaDownloadService.DownloadResult.failure(
                    "⚠️ Bu platforma qo'llab-quvvatlanmaydi yoki video mavjud emas."
                );
            }
            File file = downloadFromUrl(downloadUrl, "cobalt_video");
            if (file == null || !file.exists()) {
                return MediaDownloadService.DownloadResult.failure(
                    "❌ Video yuklab olinmadi."
                );
            }
            log.info("Cobalt downloaded: {}MB", file.length() / 1024 / 1024);
            return MediaDownloadService.DownloadResult.success(
                file, "Video", "video"
            );
        } catch (Exception e) {
            log.error("Cobalt download failed", e);
            return MediaDownloadService.DownloadResult.failure(
                "❌ Yuklab olishda xatolik: " + e.getMessage()
            );
        }
    }
}

