package uz.chaqmoq.media.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.User;
import uz.chaqmoq.media.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final BotUserRepository userRepository;
    private final DownloadHistoryRepository downloadHistoryRepository;

    public BotUser saveOrUpdate(User telegramUser) {
        Optional<BotUser> existing = userRepository.findById(telegramUser.getId());

        if (existing.isPresent()) {
            BotUser user = existing.get();
            user.setUsername(telegramUser.getUserName());
            user.setFirstName(telegramUser.getFirstName());
            user.setLastName(telegramUser.getLastName());
            user.setLastActive(LocalDateTime.now());
            return userRepository.save(user);
        }

        BotUser newUser = BotUser.builder()
                .telegramId(telegramUser.getId())
                .username(telegramUser.getUserName())
                .firstName(telegramUser.getFirstName())
                .lastName(telegramUser.getLastName())
                .build();
        return userRepository.save(newUser);
    }

    public Optional<BotUser> findById(Long telegramId) {
        return userRepository.findById(telegramId);
    }

    public boolean isBanned(Long telegramId) {
        return userRepository.findById(telegramId)
                .map(BotUser::isBanned)
                .orElse(false);
    }

    public void recordDownload(Long userId, String url, String platform, String mediaType,
                               boolean success, String error) {
        DownloadHistory history = DownloadHistory.builder()
                .userId(userId)
                .url(url)
                .platform(platform)
                .mediaType(mediaType)
                .success(success)
                .errorMessage(error)
                .build();
        downloadHistoryRepository.save(history);

        if (success) {
            userRepository.findById(userId).ifPresent(user -> {
                user.setDownloadCount(user.getDownloadCount() + 1);
                userRepository.save(user);
            });
        }
    }

    public long getTotalUsers() {
        return userRepository.count();
    }

    public long getActiveUsers() {
        return userRepository.countActiveUsers();
    }

    public long getTotalDownloads() {
        return downloadHistoryRepository.countSuccessful();
    }

    public String getState(Long userId) {
        return userRepository.findById(userId)
                .map(BotUser::getState)
                .orElse("IDLE");
    }

    public void setState(Long userId, String state) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setState(state);
            userRepository.save(user);
        });
    }

    public long getTodayDownloads() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        return downloadHistoryRepository
            .countByCreatedAtBetween(startOfDay, endOfDay);
    }

    public long getSuccessDownloads() {
        return downloadHistoryRepository.countBySuccessTrue();
    }

    public long getFailedDownloads() {
        return downloadHistoryRepository.countBySuccessFalse();
    }
}
