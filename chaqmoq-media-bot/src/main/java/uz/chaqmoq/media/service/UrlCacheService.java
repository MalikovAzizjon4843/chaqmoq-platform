package uz.chaqmoq.media.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UrlCacheService {

    private static final int MAX_CACHE_SIZE = 1000;

    private final Map<String, String> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public String store(String url) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        cache.put(id, url);
        return id;
    }

    public String get(String id) {
        return cache.get(id);
    }

    public String createCallbackData(String action, String url) {
        String id = store(url);
        return action + ":" + id;
    }

    public String resolveUrl(String callbackData) {
        if (callbackData == null || !callbackData.contains(":")) return null;
        String id = callbackData.substring(callbackData.indexOf(":") + 1);
        return get(id);
    }
}
