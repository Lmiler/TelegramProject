package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.util.*;

public class SurveyBot extends TelegramLongPollingBot {

    private Map<Long, String> users = new HashMap<>();
    private Map<String, List<String>> surveyCreation = new HashMap<>();
    private Map<Long, Map<String, String>> responses = new HashMap<>();
    private List<Map<String, Object>> activeSurvey = null;
    private Long surveyCreator = null;
    private final String USERS_FILE = "community_members.txt"; // File to store users

    private int questionCount;
    private int currentQuestion;
    private List<String> questions = new ArrayList<>();
    private Map<String, List<String>> questionOptions = new HashMap<>();

    private String botUsername;
    private String botToken;

    // Constructor to load users from file
    public SurveyBot(String username, String token) {
        this.botUsername = username;
        this.botToken = token;
        loadUsersFromFile();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            Long userId = update.getMessage().getFrom().getId();
            String username = update.getMessage().getFrom().getFirstName();
            Long chatId = update.getMessage().getChatId();

            if (messageText.equals("/start")) {
                handleStartCommand(chatId, userId, username);
            } else if (messageText.equals("/create_survey")) {
                handleCreateSurvey(chatId, userId);
            } else if (surveyCreation.containsKey("state")) {
                handleSurveyCreationProcess(chatId, messageText, userId);
            } else if (messageText.equals("/show_results")) {
                handleShowResults(chatId, userId);
            }
        }
        if (update.hasCallbackQuery()) {
            handleSurveyResponse(update.getCallbackQuery().getFrom().getId(),
                    update.getCallbackQuery().getData(), update.getCallbackQuery().getMessage().toString());
        }
    }

    // Start command: Add user to community if not already registered
    private void handleStartCommand(Long chatId, Long userId, String username) {
        if (!users.containsKey(userId)) {
            users.put(userId, username);
            sendMessage(chatId, "Welcome " + username + "! You are now part of the community!");
            saveUserToFile(userId, username);
            notifyCommunity(username);
        } else {
            sendMessage(chatId, "You are already registered in the community.");
        }
    }

    // Create survey command: Start survey creation process
    private void handleCreateSurvey(Long chatId, Long userId) {
        if (users.size() < 3) {
            sendMessage(chatId, "You need at least 3 members to create a survey.");
            return;
        }
        if (activeSurvey != null) {
            sendMessage(chatId, "There is already an active survey. Please wait for it to finish.");
            return;
        }
        surveyCreation.put("state", Collections.singletonList("ASK_QUESTION_COUNT"));
        sendMessage(chatId, "How many questions will the survey have? (Choose between 1 and 3)");
    }

    // Handle the process of creating a survey step by step
    private void handleSurveyCreationProcess(Long chatId, String messageText, Long userId) {
        String state = surveyCreation.get("state").get(0);
        switch (state) {
            case "ASK_QUESTION_COUNT":
                try {
                    questionCount = Integer.parseInt(messageText);
                    if (questionCount < 1 || questionCount > 3) {
                        throw new Exception();
                    }
                    surveyCreation.put("state", Collections.singletonList("ASK_QUESTION_TEXT"));
                    currentQuestion = 1;
                    sendMessage(chatId, "Enter text for question 1:");
                } catch (Exception e) {
                    sendMessage(chatId, "Please enter a number between 1 and 3.");
                }
                break;

            case "ASK_QUESTION_TEXT":
                questions.add(messageText);
                sendMessage(chatId, "Enter the options for question " + currentQuestion + " (2-4 options, " +
                        "separated by commas):");
                surveyCreation.put("state", Collections.singletonList("ASK_QUESTION_OPTIONS"));
                break;

            case "ASK_QUESTION_OPTIONS":
                String[] options = messageText.split(",");
                if (options.length < 2 || options.length > 4) {
                    sendMessage(chatId, "Please enter between 2 and 4 options, separated by commas.");
                } else {
                    questionOptions.put(questions.get(currentQuestion - 1), Arrays.asList(options));
                    if (currentQuestion < questionCount) {
                        currentQuestion++;
                        sendMessage(chatId, "Enter text for question " + currentQuestion + ":");
                        surveyCreation.put("state", Collections.singletonList("ASK_QUESTION_TEXT"));
                    } else {
                        surveyCreation.remove("state");
                        sendMessage(chatId, "Survey creation is complete. Sending the survey...");
                        sendSurvey(userId);
                    }
                }
                break;
        }
    }

    // Handle the survey response from the user
    private void handleSurveyResponse(Long userId, String answer, String questionText) {
        // Check if the user has already answered this question
        Map<String, String> userResponses = responses.computeIfAbsent(userId, k -> new HashMap<>());

        if (userResponses.containsKey(questionText)) {
            // User has already answered, send a message saying only the first answer counts
            sendMessage(userId, "You have already answered this question. Only your first answer counts.");
        } else {
            // First time answering, record the answer
            userResponses.put(questionText.toLowerCase().trim(), answer);
            responses.put(userId, userResponses);
            sendMessage(userId, "Thank you for your answer!");
        }
    }

    // Handle showing the survey results
    private void handleShowResults(Long chatId, Long userId) {
        // Ensure there's an active survey and the user requesting results is the creator
        if (activeSurvey == null || !(userId.equals(surveyCreator))) {
            sendMessage(chatId, "No active survey or you're not the creator.");
            return;
        }

        StringBuilder results = new StringBuilder("Survey Results:\n");

        for (Map<String, Object> question : activeSurvey) {
            String questionText = (String) question.get("text");
            List<String> options = ((List<?>) question.get("options"))
                    .stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();

            Map<String, Integer> answerCounts = new HashMap<>();
            options.forEach(option -> answerCounts.put(option, 0));

            int totalResponsesForQuestion = 0;  // Track how many people answered this question

            // Count responses for this specific question
            for (Map<String, String> userResponses : responses.values()) {
                String answer = userResponses.get(questionText.toLowerCase().trim());
                if (answer != null) {
                    answerCounts.put(answer, answerCounts.getOrDefault(answer, 0) + 1);
                    totalResponsesForQuestion++;  // Increment response count for the question
                }
            }

            results.append("Question: ").append(questionText).append("\n");

            // Calculate percentage of responses for each option based on responses to this question
            for (String option : options) {
                int count = answerCounts.get(option);
                double percentage = (totalResponsesForQuestion > 0)
                        ? (count * 100.0) / totalResponsesForQuestion
                        : 0.0;

                results.append(option).append(": ")
                        .append(String.format("%.2f", percentage)).append("% (")
                        .append(count).append(" responses)\n");
            }
            results.append("\n");
        }

        sendMessage(chatId, results.toString());
        activeSurvey = null;  // Reset after showing results
    }


    // Broadcast the survey to the community
    private void sendSurvey(Long userId) {
        activeSurvey = new ArrayList<>();
        for (String questionText : questions) {
            Map<String, Object> question = new HashMap<>();
            question.put("text", questionText);
            question.put("options", questionOptions.get(questionText));
            activeSurvey.add(question);

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
            questionOptions.get(questionText).forEach(option -> {
                List<InlineKeyboardButton> row = new ArrayList<>();
                InlineKeyboardButton button = new InlineKeyboardButton();
                button.setText(option);
                button.setCallbackData(option);
                row.add(button);
                keyboard.add(row);
            });
            markup.setKeyboard(keyboard);

            for (Long userID : users.keySet()) {
                sendMessageWithMarkup(userID, questionText, markup);
            }
        }
        surveyCreator = userId;
    }

    // Notify all community members when someone joins
    private void notifyCommunity(String username) {
        String message = username + " joined the community! We now have " + users.size() + " members.";
        for (Long userId : users.keySet()) {
            sendMessage(userId, message);
        }
    }

    // Send a message to a user with an InlineKeyboardMarkup (for survey options)
    private void sendMessageWithMarkup(Long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setReplyMarkup(markup);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Send a plain text message
    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Save user to file
    private void saveUserToFile(Long userId, String username) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(USERS_FILE, true))) {
            writer.write(userId + "," + username);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Load users from file
    private void loadUsersFromFile() {
        File file = new File(USERS_FILE);
        if (!file.exists()) {
            return; // No file, nothing to load
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(USERS_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    Long userId = Long.parseLong(parts[0]);
                    String username = parts[1];
                    users.put(userId, username);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Bot username
    @Override
    public String getBotUsername() {
        return this.botUsername;
    }

    // Bot token
    @Override
    public String getBotToken() {
        return this.botToken;
    }
}
