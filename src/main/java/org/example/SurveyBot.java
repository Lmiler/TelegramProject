package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.util.*;

public class SurveyBot extends TelegramLongPollingBot {

    private Map<Long, String> users = new HashMap<>();
    private Map<Long, Integer> userCurrentQuestion = new HashMap<>();
    private Map<Long, Map<String, String>> responses = new HashMap<>();
    private List<String> questions = new ArrayList<>();
    private Map<String, List<String>> questionOptions = new HashMap<>();
    private Map<Long, String> surveyCreationState = new HashMap<>();
    private Map<Long, Integer> surveyQuestionCount = new HashMap<>();
    private Long surveyCreator = null;
    private final String USERS_FILE = "community_members.txt";

    private String botUsername;
    private String botToken;

    public SurveyBot(String username, String token) {
        this.botUsername = username;
        this.botToken = token;
        loadUsersFromFile();
        sendInstructionsToAllUsers();
    }

    public void sendInstructionsToAllUsers() {
        for (Long userId : users.keySet()) {
            sendInstructions(userId);
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            Long userId = update.getMessage().getFrom().getId();
            String username = update.getMessage().getFrom().getFirstName();
            Long chatId = update.getMessage().getChatId();

            if (messageText.equals("/start") || messageText.equalsIgnoreCase("hi")
                    || messageText.equals("היי")) {
                handleStartCommand(chatId, userId, username);
            } else if (messageText.equals("/create_survey")) {
                handleCreateSurveyCommand(chatId, userId);
            } else if (messageText.equals("/show_results")) {
                handleShowResults(chatId, userId);
            } else if (messageText.equals("/help") || messageText.equals("/instructions")) {
                sendInstructions(chatId);
            } else if (surveyCreationState.containsKey(userId)) {
                handleSurveyCreationProcess(userId, messageText.trim(), chatId);
            } else if (userCurrentQuestion.containsKey(userId)) {
                handleSurveyResponse(userId, messageText.trim(), chatId);
            }
        }
    }

    private void handleStartCommand(Long chatId, Long userId, String username) {
        if (!users.containsKey(userId)) {
            users.put(userId, username);
            sendMessage(chatId, "Welcome " + username + "! You are now part of the community!");
            sendInstructions(chatId);
            saveUserToFile(userId, username);
            notifyCommunity(username);
        } else {
            sendMessage(chatId, "You are already registered in the community.");
        }
    }

    private void sendInstructions(Long chatId) {
        String instructions = "Here are the instructions to use the bot:\n\n" +
                "/start - Register yourself in the community and start interacting with the bot.\n" +
                "/create_survey - Create a new survey by specifying the number of questions and providing options for each question.\n" +
                "/show_results - If you created the survey, you can view the results.\n" +
                "/help or /instructions - Show these instructions again.\n\n" +
                "After creating a survey, users will receive each question one at a time and can respond by typing one of the provided options.";

        sendMessage(chatId, instructions);  // Send the instructions to the user
    }

    private void handleCreateSurveyCommand(Long chatId, Long userId) {
        if (users.size() < 3) {
            sendMessage(chatId, "You need at least 3 members to create a survey.");
            return;
        }
        if (surveyCreator != null) {
            sendMessage(chatId, "There is already an active survey. Please wait for it to finish.");
            return;
        }

        surveyCreator = userId;  // Mark this user as the survey creator
        surveyCreationState.put(userId, "ASK_QUESTION_COUNT");
        sendMessage(chatId, "How many questions will the survey have? (1 to 3)");
    }

    private void handleSurveyCreationProcess(Long userId, String messageText, Long chatId) {
        String state = surveyCreationState.get(userId);

        switch (state) {
            case "ASK_QUESTION_COUNT":
                try {
                    int questionCount = Integer.parseInt(messageText);
                    if (questionCount < 1 || questionCount > 3) {
                        throw new Exception();
                    }
                    surveyQuestionCount.put(userId, questionCount);
                    surveyCreationState.put(userId, "ASK_QUESTION_TEXT");
                    sendMessage(chatId, "Enter text for question 1:");
                } catch (Exception e) {
                    sendMessage(chatId, "Please enter a valid number between 1 and 3.");
                }
                break;

            case "ASK_QUESTION_TEXT":
                int currentQuestionNumber = questions.size() + 1;
                questions.add(messageText);
                sendMessage(chatId, "Enter the options for question " + currentQuestionNumber + " (separate by commas):");
                surveyCreationState.put(userId, "ASK_QUESTION_OPTIONS");
                break;

            case "ASK_QUESTION_OPTIONS":
                String[] options = messageText.split(",");
                if (options.length < 2 || options.length > 4) {
                    sendMessage(chatId, "Please enter between 2 and 4 options, separated by commas.");
                } else {
                    String currentQuestion = questions.get(questions.size() - 1);
                    questionOptions.put(currentQuestion, Arrays.asList(options));

                    if (questions.size() < surveyQuestionCount.get(userId)) {
                        sendMessage(chatId, "Enter text for question " + (questions.size() + 1) + ":");
                        surveyCreationState.put(userId, "ASK_QUESTION_TEXT");
                    } else {
                        surveyCreationState.remove(userId);
                        sendMessage(chatId, "Survey creation complete. Sending the survey to all users...");
                        startSurveyForAllUsers();
                    }
                }
                break;
        }
    }

    private void startSurveyForAllUsers() {
        System.out.println("Starting survey for all users...");
        for (Long userId : users.keySet()) {
            userCurrentQuestion.put(userId, 0);
            sendNextQuestion(userId);
        }
    }

    private void sendNextQuestion(Long userId) {
        int currentQuestionIndex = userCurrentQuestion.get(userId);

        if (currentQuestionIndex < questions.size()) {
            String questionText = questions.get(currentQuestionIndex);
            sendMessage(userId, "Question: " + questionText + "\nOptions: " + String.join(", ", questionOptions.get(questionText)));
        } else {
            // Survey is complete for this user
            sendMessage(userId, "Thank you for completing the survey!");
            userCurrentQuestion.remove(userId);
        }
    }

    private void handleSurveyResponse(Long userId, String userResponse, Long chatId) {
        int currentQuestionIndex = userCurrentQuestion.get(userId);
        String questionText = questions.get(currentQuestionIndex);
        List<String> options = questionOptions.get(questionText);

        if (options.contains(userResponse.trim())) {
            // Save user response
            Map<String, String> userResponses = responses.computeIfAbsent(userId, k -> new HashMap<>());
            userResponses.put(questionText, userResponse.trim());
            responses.put(userId, userResponses);

            sendMessage(chatId, "Thank you for your response!");

            userCurrentQuestion.put(userId, currentQuestionIndex + 1);
            sendNextQuestion(userId);
        } else {
            sendMessage(chatId, "Invalid response. Please answer with one of the options: " + String.join(", ", options));
        }
    }

    private void handleShowResults(Long chatId, Long userId) {
        if (surveyCreator == null || !(userId.equals(surveyCreator))) {
            sendMessage(chatId, "No active survey or you're not the creator.");
            return;
        }

        StringBuilder results = new StringBuilder("Survey Results:\n");

        for (String questionText : questions) {
            List<String> options = questionOptions.get(questionText);

            Map<String, Integer> answerCounts = new HashMap<>();
            options.forEach(option -> answerCounts.put(option, 0));

            int totalResponsesForQuestion = 0;

            for (Map<String, String> userResponses : responses.values()) {
                String answer = userResponses.get(questionText);
                if (answer != null) {
                    answerCounts.put(answer, answerCounts.get(answer) + 1);
                    totalResponsesForQuestion++;
                }
            }

            results.append("Question: ").append(questionText).append("\n");

            for (String option : options) {
                int count = answerCounts.getOrDefault(option, 0);
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
        surveyCreator = null;
    }

    private void notifyCommunity(String username) {
        String message = username + " joined the community! We now have " + users.size() + " members.";
        for (Long userId : users.keySet()) {
            sendMessage(userId, message);
        }
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Error sending message to chat ID: " + chatId + " - " + e.getMessage());
        }
    }

    private void saveUserToFile(Long userId, String username) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(USERS_FILE, true))) {
            writer.write(userId + "," + username);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadUsersFromFile() {
        File file = new File(USERS_FILE);
        if (!file.exists()) {
            return;
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

    @Override
    public String getBotUsername() {
        return this.botUsername;
    }

    @Override
    public String getBotToken() {
        return this.botToken;
    }
}
