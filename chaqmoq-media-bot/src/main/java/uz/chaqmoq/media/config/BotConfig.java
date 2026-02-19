package uz.chaqmoq.media.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import uz.chaqmoq.media.handler.ChaqmoqBot;

@Slf4j
@Configuration
public class BotConfig {

    @Bean
    public TelegramBotsApi telegramBotsApi(ChaqmoqBot chaqmoqBot) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        try {
            api.registerBot(chaqmoqBot);
            log.info("Bot muvaffaqiyatli ro'yxatdan o'tdi: {}", chaqmoqBot.getBotUsername());
        } catch (TelegramApiException e) {
            log.error("Botni ro'yxatdan o'tkazishda xato: {}", e.getMessage(), e);
            throw e;
        }
        return api;
    }
}
