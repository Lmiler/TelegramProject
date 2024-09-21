package org.example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        final String username = "SurveyBot";
        final String token = "7472975768:AAFKnjOcRoM4IkLvgrpJdCILX-HNe-FvJ9s";
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new SurveyBot(username, token));
            System.out.println("Bot is up and running!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
