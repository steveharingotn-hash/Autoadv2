package com.replica;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public class Bot extends ListenerAdapter {

    private static final String SLOTS_FILE = "slots.json";
    private static JSONObject slotsData = new JSONObject();
    private static final Map<String, ScheduledFuture<?>> activeSlots = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isEmpty()) {
            System.err.println("❌ Missing DISCORD_TOKEN in Railway Environment Variables!");
            System.exit(1);
        }

        loadSlots();

        JDA jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Bot())
                .build();

        jda.awaitReady();
        jda.updateCommands().addCommands(
                Commands.slash("panel", "Open Replica Ad Panel")
        ).queue();
        
        System.out.println("✅ Java Control Bot Online -> " + jda.getSelfUser().getAsTag());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("panel")) {
            event.reply("🔧 **REPLICA CONTROL PANEL**\nAuto Advertising System")
                    .addActionRow(
                            Button.success("start_1", "🚀 Start Slot 1"),
                            Button.danger("stop_1", "⭕ Stop Slot 1"),
                            Button.secondary("setup_1", "⚙️ Setup Slot 1")
                    ).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();

        if (componentId.equals("setup_1")) {
            JSONObject data = slotsData.optJSONObject("1");
            String defaultToken = data != null ? data.optString("token", "") : "";
            String defaultChannels = data != null ? String.join(", ", jsonArrayToList(data.optJSONArray("channels"))) : "";
            String defaultDelay = data != null ? String.valueOf(data.optInt("delay", 30)) : "30";
            String defaultMsg = data != null ? data.optString("message", "") : "";

            TextInput tokenInput = TextInput.create("t_token", "Slot Bot Token", TextInputStyle.SHORT).setValue(defaultToken).setRequired(true).build();
            TextInput channelsInput = TextInput.create("t_channels", "Channel IDs (comma separated)", TextInputStyle.SHORT).setValue(defaultChannels).setRequired(true).build();
            TextInput delayInput = TextInput.create("t_delay", "Delay in seconds", TextInputStyle.SHORT).setValue(defaultDelay).setRequired(true).build();
            TextInput msgInput = TextInput.create("t_message", "Ad Message", TextInputStyle.PARAGRAPH).setValue(defaultMsg).setRequired(true).build();

            Modal modal = Modal.create("modal_setup_1", "Setup Slot 1 - Token Configuration")
                    .addComponents(ActionRow.of(tokenInput), ActionRow.of(channelsInput), ActionRow.of(delayInput), ActionRow.of(msgInput))
                    .build();

            event.replyModal(modal).queue();
            
        } else if (componentId.equals("start_1")) {
            if (!slotsData.has("1")) {
                event.reply("❌ Please Setup Slot 1 first!").setEphemeral(true).queue();
                return;
            }

            stopSlot("1");
            startSlot("1");
            event.reply("🚀 Starting Slot 1 Ad automation...").setEphemeral(true).queue();

        } else if (componentId.equals("stop_1")) {
            if (activeSlots.containsKey("1")) {
                stopSlot("1");
                event.reply("⛔ Slot 1 Stopped.").setEphemeral(true).queue();
            } else {
                event.reply("Slot 1 is not running.").setEphemeral(true).queue();
            }
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().equals("modal_setup_1")) {
            String token = event.getValue("t_token").getAsString().strip();
            String channelsStr = event.getValue("t_channels").getAsString();
            String delayStr = event.getValue("t_delay").getAsString().strip();
            String message = event.getValue("t_message").getAsString().strip();

            int delay;
            try {
                delay = Math.max(Integer.parseInt(delayStr), 5);
            } catch (NumberFormatException e) {
                event.reply("❌ Delay must be a valid number!").setEphemeral(true).queue();
                return;
            }

            List<String> channels = Arrays.stream(channelsStr.split(","))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();

            JSONObject slotConfig = new JSONObject();
            slotConfig.put("token", token);
            slotConfig.put("channels", channels);
            slotConfig.put("delay", delay);
            slotConfig.put("message", message);

            slotsData.put("1", slotConfig);
            saveSlots();

            event.reply("✅ Settings Saved! Click **Start Slot 1**").setEphemeral(true).queue();
        }
    }

    private void startSlot(String slotId) {
        JSONObject data = slotsData.getJSONObject(slotId);
        String token = data.getString("token");
        List<String> channels = jsonArrayToList(data.getJSONArray("channels"));
        int delayVal = data.getInt("delay");
        String message = data.getString("message");

        Runnable task = () -> {
            for (String channelId : channels) {
                try {
                    String jsonPayload = new JSONObject().put("content", message).toString();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages"))
                            .header("Authorization", "Bot " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        System.out.println("[Slot " + slotId + "] ✅ Sent to #" + channelId);
                    } else {
                        System.err.println("[Slot " + slotId + "] ⚠️ Failed channel " + channelId + ". Status: " + response.statusCode());
                    }
                } catch (Exception e) {
                    System.err.println("[Slot " + slotId + "] Post Error: " + e.getMessage());
                }
            }
        };

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(task, 0, delayVal, TimeUnit.SECONDS);
        activeSlots.put(slotId, future);
    }

    private void stopSlot(String slotId) {
        ScheduledFuture<?> future = activeSlots.remove(slotId);
        if (future != null) {
            future.cancel(true);
            System.out.println("[Slot " + slotId + "] 🛑 Loop stopped.");
        }
    }

    private static void loadSlots() {
        try {
            File file = new File(SLOTS_FILE);
            if (file.exists()) {
                String content = new String(Files.readAllBytes(Paths.get(SLOTS_FILE)));
                slotsData = new JSONObject(content);
            }
        } catch (Exception e) {
            slotsData = new JSONObject();
        }
    }

    private static void saveSlots() {
        try (FileWriter file = new FileWriter(SLOTS_FILE)) {
            file.write(slotsData.toString(4));
        } catch (Exception e) {
            System.err.println("Error saving slots file: " + e.getMessage());
        }
    }

    private List<String> jsonArrayToList(org.json.JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                list.add(array.getString(i));
            }
        }
        return list;
    }
}