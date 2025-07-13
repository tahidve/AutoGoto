package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class AutoGotoClient implements ClientModInitializer {
    private final List<BlockPos> coordinates = new ArrayList<>();
    
    // Key binding
    private static KeyBinding toggleAutoGotoKey;
    
    // Settings
    private int currentIndex = 0;
    private boolean isAutoGotoEnabled = false;
    private int checkInterval = 0;
    private String customMessage = "/home";
    
    // Loop settings
    private boolean isLoopMode = false;  // false = chạy 1 lần, true = chạy vòng lặp
    private int loopDelaySeconds = 5;    // Chờ 5 giây giữa các vòng lặp
    private int loopDelayTicks = 0;      // Counter cho delay
    private boolean isWaitingForNextLoop = false;
    private int completedLoops = 0;      // Số vòng lặp đã hoàn thành
    
    // Home delay settings
    private boolean isWaitingForHome = false;
    private int homeDelayTicks = 0;
    private final int HOME_DELAY_SECONDS = 3; // 3 giây cố định
    
    // File system
    private static final String CONFIG_DIR = "config/autogoto";
    private static final String WAYPOINTS_DIR = CONFIG_DIR + "/waypoints";
    private static final String SETTINGS_FILE = CONFIG_DIR + "/settings.json";
    private static final String CURRENT_WAYPOINTS_FILE = CONFIG_DIR + "/current.json";
    
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    @Override
    public void onInitializeClient() {
        // Tạo key binding
        toggleAutoGotoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autogoto.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.autogoto"
        ));
        
        // Tạo thư mục config
        createConfigDirectories();
        
        // Load settings và waypoints
        loadSettings();
        loadCurrentWaypoints();
        
        // Đăng ký các commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("autogoto")
                .then(ClientCommandManager.literal("start")
                    .executes(this::startAutoGotoCommand))
                .then(ClientCommandManager.literal("stop")
                    .executes(this::stopAutoGotoCommand))
                .then(ClientCommandManager.literal("add")
                    .executes(this::addPositionCommand))
                .then(ClientCommandManager.literal("clear")
                    .executes(this::clearPositionsCommand))
                .then(ClientCommandManager.literal("list")
                    .executes(this::listPositionsCommand))
                .then(ClientCommandManager.literal("toggle")
                    .executes(this::toggleAutoGotoCommand))
                .then(ClientCommandManager.literal("message")
                    .then(ClientCommandManager.argument("msg", StringArgumentType.greedyString())
                        .executes(this::setCustomMessageCommand))
                    .executes(this::showCustomMessageCommand))
                .then(ClientCommandManager.literal("mode")
                    .then(ClientCommandManager.literal("once")
                        .executes(this::setOnceModeCommand))
                    .then(ClientCommandManager.literal("loop")
                        .executes(this::setLoopModeCommand))
                    .executes(this::showModeCommand))
                .then(ClientCommandManager.literal("delay")
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 300))
                        .executes(this::setDelayCommand))
                    .executes(this::showDelayCommand))
                .then(ClientCommandManager.literal("settings")
                    .executes(this::showSettingsCommand))
                // FILE MANAGEMENT COMMANDS
                .then(ClientCommandManager.literal("save")
                    .then(ClientCommandManager.argument("filename", StringArgumentType.word())
                        .executes(this::saveWaypointsCommand))
                    .executes(this::saveWaypointsWithDateCommand))
                .then(ClientCommandManager.literal("load")
                    .then(ClientCommandManager.argument("filename", StringArgumentType.word())
                        .executes(this::loadWaypointsCommand)))
                .then(ClientCommandManager.literal("delete")
                    .then(ClientCommandManager.argument("filename", StringArgumentType.word())
                        .executes(this::deleteWaypointsCommand)))
                .then(ClientCommandManager.literal("files")
                    .executes(this::listFilesCommand))
                .then(ClientCommandManager.literal("export")
                    .then(ClientCommandManager.argument("filename", StringArgumentType.word())
                        .executes(this::exportWaypointsCommand)))
                .then(ClientCommandManager.literal("import")
                    .then(ClientCommandManager.argument("filename", StringArgumentType.word())
                        .executes(this::importWaypointsCommand)))
                .executes(this::showHelpCommand));
        });
        
        // Đăng ký client tick event
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        
        System.out.println("AutoGoto loaded with file system!");
    }
    
    // FILE SYSTEM METHODS
    private void createConfigDirectories() {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            Files.createDirectories(Paths.get(WAYPOINTS_DIR));
            System.out.println("AutoGoto: Created config directories");
        } catch (IOException e) {
            System.err.println("AutoGoto: Failed to create config directories: " + e.getMessage());
        }
    }
    
    private void saveSettings() {
        try {
            Map<String, Object> settings = new HashMap<>();
            settings.put("isLoopMode", isLoopMode);
            settings.put("loopDelaySeconds", loopDelaySeconds);
            settings.put("customMessage", customMessage);
            
            String json = gson.toJson(settings);
            Files.write(Paths.get(SETTINGS_FILE), json.getBytes());
        } catch (IOException e) {
            System.err.println("AutoGoto: Failed to save settings: " + e.getMessage());
        }
    }
    
private void loadSettings() {
    try {
        if (Files.exists(Paths.get(SETTINGS_FILE))) {
            String json = new String(Files.readAllBytes(Paths.get(SETTINGS_FILE)));
            Map<String, Object> settings = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            
            if (settings != null) {
                // Safe parsing với null check
                Object loopModeObj = settings.get("isLoopMode");
                if (loopModeObj != null) {
                    isLoopMode = (Boolean) loopModeObj;
                }
                
                Object delayObj = settings.get("loopDelaySeconds");
                if (delayObj != null) {
                    loopDelaySeconds = ((Number) delayObj).intValue();
                }
                
                Object messageObj = settings.get("customMessage");
                if (messageObj != null) {
                    customMessage = (String) messageObj;
                }
            }
            System.out.println("AutoGoto: Loaded settings");
        }
    } catch (Exception e) {
        System.err.println("AutoGoto: Failed to load settings: " + e.getMessage());
        e.printStackTrace();
    }
}
    
private void saveCurrentWaypoints() {
    try {
        // Convert BlockPos to simple coordinate objects
        List<Map<String, Integer>> coordList = new ArrayList<>();
        for (BlockPos pos : coordinates) {
            Map<String, Integer> coord = new HashMap<>();
            coord.put("x", pos.getX());
            coord.put("y", pos.getY());
            coord.put("z", pos.getZ());
            coordList.add(coord);
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("coordinates", coordList);
        data.put("currentIndex", currentIndex);
        data.put("savedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        String json = gson.toJson(data);
        Files.write(Paths.get(CURRENT_WAYPOINTS_FILE), json.getBytes());
    } catch (IOException e) {
        System.err.println("AutoGoto: Failed to save current waypoints: " + e.getMessage());
    }
}
    
private void loadCurrentWaypoints() {
    try {
        if (Files.exists(Paths.get(CURRENT_WAYPOINTS_FILE))) {
            String json = new String(Files.readAllBytes(Paths.get(CURRENT_WAYPOINTS_FILE)));
            Map<String, Object> data = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            
            if (data != null && data.containsKey("coordinates")) {
                coordinates.clear();
                List<Map<String, Object>> coords = (List<Map<String, Object>>) data.get("coordinates");
                
                if (coords != null) {
                    for (Map<String, Object> coord : coords) {
                        if (coord != null) {
                            // Safe parsing với null check
                            Object xObj = coord.get("x");
                            Object yObj = coord.get("y");
                            Object zObj = coord.get("z");
                            
                            if (xObj != null && yObj != null && zObj != null) {
                                int x = ((Number) xObj).intValue();
                                int y = ((Number) yObj).intValue();
                                int z = ((Number) zObj).intValue();
                                coordinates.add(new BlockPos(x, y, z));
                            }
                        }
                    }
                }
                
                // Safe parsing currentIndex
                Object indexObj = data.get("currentIndex");
                if (indexObj != null) {
                    currentIndex = ((Number) indexObj).intValue();
                } else {
                    currentIndex = 0;
                }
                
                System.out.println("AutoGoto: Loaded " + coordinates.size() + " waypoints");
            }
        }
    } catch (Exception e) {
        System.err.println("AutoGoto: Failed to load current waypoints: " + e.getMessage());
        e.printStackTrace();
    }
}
    
private void saveWaypointsToFile(String filename) {
    try {
        // Convert BlockPos to simple coordinate objects
        List<Map<String, Integer>> coordList = new ArrayList<>();
        for (BlockPos pos : coordinates) {
            Map<String, Integer> coord = new HashMap<>();
            coord.put("x", pos.getX());
            coord.put("y", pos.getY());
            coord.put("z", pos.getZ());
            coordList.add(coord);
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("name", filename);
        data.put("coordinates", coordList);
        data.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        data.put("count", coordinates.size());
        data.put("settings", Map.of(
            "isLoopMode", isLoopMode,
            "loopDelaySeconds", loopDelaySeconds,
            "customMessage", customMessage
        ));
        
        String json = gson.toJson(data);
        String filepath = WAYPOINTS_DIR + "/" + filename + ".json";
        Files.write(Paths.get(filepath), json.getBytes());
        System.out.println("AutoGoto: Saved " + coordinates.size() + " waypoints to " + filename + ".json");
    } catch (IOException e) {
        System.err.println("AutoGoto: Failed to save waypoints to file: " + e.getMessage());
    }
}
    
private boolean loadWaypointsFromFile(String filename) {
    try {
        String filepath = WAYPOINTS_DIR + "/" + filename + ".json";
        if (!Files.exists(Paths.get(filepath))) {
            return false;
        }
        
        String json = new String(Files.readAllBytes(Paths.get(filepath)));
        Map<String, Object> data = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
        
        if (data != null && data.containsKey("coordinates")) {
            coordinates.clear();
            List<Map<String, Object>> coords = (List<Map<String, Object>>) data.get("coordinates");
            
            if (coords != null) {
                for (Map<String, Object> coord : coords) {
                    if (coord != null) {
                        // Try both formats: standard (x,y,z) and Minecraft field names
                        Object xObj = coord.get("x");
                        Object yObj = coord.get("y");
                        Object zObj = coord.get("z");
                        
                        // If standard format doesn't work, try Minecraft field names
                        if (xObj == null || yObj == null || zObj == null) {
                            xObj = coord.get("field_11175"); // x
                            yObj = coord.get("field_11174"); // y
                            zObj = coord.get("field_11173"); // z
                        }
                        
                        if (xObj != null && yObj != null && zObj != null) {
                            int x = ((Number) xObj).intValue();
                            int y = ((Number) yObj).intValue();
                            int z = ((Number) zObj).intValue();
                            coordinates.add(new BlockPos(x, y, z));
                            System.out.println("AutoGoto: Loaded coordinate: " + x + ", " + y + ", " + z);
                        } else {
                            System.out.println("AutoGoto: Failed to parse coordinate: " + coord);
                        }
                    }
                }
            }
            
            System.out.println("AutoGoto: Total coordinates loaded: " + coordinates.size());
            
            // Load settings if available với null check
            if (data.containsKey("settings")) {
                Map<String, Object> settings = (Map<String, Object>) data.get("settings");
                if (settings != null) {
                    // Safe parsing settings
                    Object loopModeObj = settings.get("isLoopMode");
                    if (loopModeObj != null) {
                        isLoopMode = (Boolean) loopModeObj;
                    }
                    
                    Object delayObj = settings.get("loopDelaySeconds");
                    if (delayObj != null) {
                        loopDelaySeconds = ((Number) delayObj).intValue();
                    }
                    
                    Object messageObj = settings.get("customMessage");
                    if (messageObj != null) {
                        customMessage = (String) messageObj;
                    }
                }
            }
            
            currentIndex = 0;
            saveCurrentWaypoints();
            saveSettings();
            return true;
        }
    } catch (Exception e) {
        System.err.println("AutoGoto: Failed to load waypoints from file: " + e.getMessage());
        e.printStackTrace();
    }
    return false;
}
    
    // COMMAND HANDLERS FOR FILE SYSTEM
    private int saveWaypointsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        String filename = StringArgumentType.getString(context, "filename");
        
        if (coordinates.isEmpty()) {
            player.sendMessage(Text.literal("§c[AutoGoto] Không có waypoints để lưu!"), false);
            return 1;
        }
        
        saveWaypointsToFile(filename);
        player.sendMessage(Text.literal("§a[AutoGoto] Đã lưu " + coordinates.size() + " waypoints vào file: " + filename + ".json"), false);
        return 1;
    }
    
    private int saveWaypointsWithDateCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        
        if (coordinates.isEmpty()) {
            player.sendMessage(Text.literal("§c[AutoGoto] Không có waypoints để lưu!"), false);
            return 1;
        }
        
        String filename = "waypoints_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        saveWaypointsToFile(filename);
        player.sendMessage(Text.literal("§a[AutoGoto] Đã lưu " + coordinates.size() + " waypoints vào file: " + filename + ".json"), false);
        return 1;
    }
    
    private int loadWaypointsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        String filename = StringArgumentType.getString(context, "filename");
        
        if (loadWaypointsFromFile(filename)) {
            player.sendMessage(Text.literal("§a[AutoGoto] Đã load " + coordinates.size() + " waypoints từ file: " + filename + ".json"), false);
        } else {
            player.sendMessage(Text.literal("§c[AutoGoto] Không tìm thấy file: " + filename + ".json"), false);
        }
        return 1;
    }
    
    private int deleteWaypointsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        String filename = StringArgumentType.getString(context, "filename");
        
        try {
            String filepath = WAYPOINTS_DIR + "/" + filename + ".json";
            if (Files.exists(Paths.get(filepath))) {
                Files.delete(Paths.get(filepath));
                player.sendMessage(Text.literal("§a[AutoGoto] Đã xóa file: " + filename + ".json"), false);
            } else {
                player.sendMessage(Text.literal("§c[AutoGoto] Không tìm thấy file: " + filename + ".json"), false);
            }
        } catch (IOException e) {
            player.sendMessage(Text.literal("§c[AutoGoto] Lỗi khi xóa file: " + e.getMessage()), false);
        }
        return 1;
    }
    
    private int listFilesCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        
        try {
            if (!Files.exists(Paths.get(WAYPOINTS_DIR))) {
                player.sendMessage(Text.literal("§e[AutoGoto] Chưa có file waypoints nào!"), false);
                return 1;
            }
            
            List<String> files = Files.list(Paths.get(WAYPOINTS_DIR))
                .filter(path -> path.toString().endsWith(".json"))
                .map(path -> path.getFileName().toString().replace(".json", ""))
                .sorted()
                .toList();
            
            if (files.isEmpty()) {
                player.sendMessage(Text.literal("§e[AutoGoto] Chưa có file waypoints nào!"), false);
            } else {
                player.sendMessage(Text.literal("§e[AutoGoto] Danh sách file waypoints (" + files.size() + "):"), false);
                for (String file : files) {
                    // Load file info
                    try {
                        String filepath = WAYPOINTS_DIR + "/" + file + ".json";
                        String json = new String(Files.readAllBytes(Paths.get(filepath)));
                        Map<String, Object> data = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
                        
                        int count = ((Double) data.getOrDefault("count", 0.0)).intValue();
                        String createdAt = (String) data.getOrDefault("createdAt", "Unknown");
                        
                        player.sendMessage(Text.literal("§7  " + file + " §f(" + count + " waypoints, " + createdAt + ")"), false);
                    } catch (Exception e) {
                        player.sendMessage(Text.literal("§7  " + file + " §c(lỗi đọc file)"), false);
                    }
                }
            }
        } catch (IOException e) {
            player.sendMessage(Text.literal("§c[AutoGoto] Lỗi khi đọc thư mục: " + e.getMessage()), false);
        }
        return 1;
    }
    
    private int exportWaypointsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        String filename = StringArgumentType.getString(context, "filename");
        
        if (coordinates.isEmpty()) {
            player.sendMessage(Text.literal("§c[AutoGoto] Không có waypoints để export!"), false);
            return 1;
        }
        
        try {
            // Export as shareable format
            StringBuilder sb = new StringBuilder();
            sb.append("# AutoGoto Waypoints Export\n");
            sb.append("# Created: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
            sb.append("# Count: ").append(coordinates.size()).append("\n");
            sb.append("# Settings: Loop=").append(isLoopMode).append(", Delay=").append(loopDelaySeconds).append("s, Message=").append(customMessage).append("\n");
            sb.append("#\n");
            
            for (int i = 0; i < coordinates.size(); i++) {
                BlockPos pos = coordinates.get(i);
                sb.append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ()).append("\n");
            }
            
            String filepath = CONFIG_DIR + "/" + filename + ".txt";
            Files.write(Paths.get(filepath), sb.toString().getBytes());
            
            player.sendMessage(Text.literal("§a[AutoGoto] Đã export " + coordinates.size() + " waypoints ra file: " + filename + ".txt"), false);
            player.sendMessage(Text.literal("§7[AutoGoto] File có thể chia sẻ tại: " + filepath), false);
        } catch (IOException e) {
            player.sendMessage(Text.literal("§c[AutoGoto] Lỗi khi export: " + e.getMessage()), false);
        }
        return 1;
    }
    
    private int importWaypointsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        String filename = StringArgumentType.getString(context, "filename");
        
        try {
            String filepath = CONFIG_DIR + "/" + filename + ".txt";
            if (!Files.exists(Paths.get(filepath))) {
                player.sendMessage(Text.literal("§c[AutoGoto] Không tìm thấy file: " + filename + ".txt"), false);
                return 1;
            }
            
            List<String> lines = Files.readAllLines(Paths.get(filepath));
            coordinates.clear();
            
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int z = Integer.parseInt(parts[2].trim());
                        coordinates.add(new BlockPos(x, y, z));
                    } catch (NumberFormatException e) {
                        player.sendMessage(Text.literal("§c[AutoGoto] Dòng không hợp lệ: " + line), false);
                    }
                }
            }
            
            if (coordinates.isEmpty()) {
                player.sendMessage(Text.literal("§c[AutoGoto] Không tìm thấy waypoints hợp lệ trong file!"), false);
            } else {
                currentIndex = 0;
                saveCurrentWaypoints();
                player.sendMessage(Text.literal("§a[AutoGoto] Đã import " + coordinates.size() + " waypoints từ file: " + filename + ".txt"), false);
            }
        } catch (IOException e) {
            player.sendMessage(Text.literal("§c[AutoGoto] Lỗi khi import: " + e.getMessage()), false);
        }
        return 1;
    }
    
    // TICK EVENTS
    private void onClientTick(MinecraftClient client) {
        if (client.player == null) return;
        
        ClientPlayerEntity player = client.player;
        
        // Check key binding
        if (toggleAutoGotoKey.wasPressed()) {
            toggleAutoGoto(player);
        }
        
        // Auto goto logic
        if (isAutoGotoEnabled) {
            if (isWaitingForHome) {
                handleHomeDelay(player);
            } else if (isWaitingForNextLoop) {
                handleLoopDelay(player);
            } else if (currentIndex < coordinates.size()) {
                checkPositionAndSendMessage(player);
            }
        }
    }
    
    private void handleHomeDelay(ClientPlayerEntity player) {
        homeDelayTicks++;
        
        // Hiển thị countdown mỗi giây
        if (homeDelayTicks % 20 == 0) {
            int remainingSeconds = HOME_DELAY_SECONDS - (homeDelayTicks / 20);
            if (remainingSeconds > 0) {
                player.sendMessage(Text.literal("§e[AutoGoto] Send message sau: " + 
                    remainingSeconds + " giây..."), true);
            }
        }
        
        // Khi hết thời gian delay
        if (homeDelayTicks >= HOME_DELAY_SECONDS * 20) {
            homeDelayTicks = 0;
            isWaitingForHome = false;
            
            // Send custom message
            player.networkHandler.sendChatMessage(customMessage);
            player.sendMessage(Text.literal("§a[AutoGoto] Đã gửi: " + customMessage), false);
            
            if (isLoopMode) {
                // Chế độ vòng lặp - chờ delay rồi tiếp tục
                isWaitingForNextLoop = true;
                loopDelayTicks = 0;
                currentIndex = 0;
                player.sendMessage(Text.literal("§b[AutoGoto] Chờ " + loopDelaySeconds + " giây để bắt đầu vòng lặp tiếp theo..."), false);
            } else {
                // Chế độ chạy 1 lần - dừng
                player.sendMessage(Text.literal("§a[AutoGoto] Hoàn thành! (Chế độ chạy 1 lần)"), false);
                stopAutoGoto(player);
            }
        }
    }
    
    private void handleLoopDelay(ClientPlayerEntity player) {
        loopDelayTicks++;
        
        // Hiển thị countdown mỗi giây
        if (loopDelayTicks % 20 == 0) {
            int remainingSeconds = loopDelaySeconds - (loopDelayTicks / 20);
            if (remainingSeconds > 0) {
                player.sendMessage(Text.literal("§e[AutoGoto] Bắt đầu vòng lặp tiếp theo sau: " + 
                    remainingSeconds + " giây..."), true);
            }
        }
        
        // Khi hết thời gian delay
        if (loopDelayTicks >= loopDelaySeconds * 20) {
            loopDelayTicks = 0;
            isWaitingForNextLoop = false;
            completedLoops++;
            
            player.sendMessage(Text.literal("§a[AutoGoto] Bắt đầu vòng lặp thứ " + (completedLoops + 1) + "!"), false);
            
            // Gửi command đầu tiên của vòng lặp mới
            BlockPos target = coordinates.get(currentIndex);
            sendGotoCommand(player, target);
        }
    }
    
    private void checkPositionAndSendMessage(ClientPlayerEntity player) {
        checkInterval++;
        if (checkInterval < 20) return; // Check mỗi giây (20 ticks)
        checkInterval = 0;
    
        BlockPos currentPos = player.getBlockPos();
        BlockPos targetPos = coordinates.get(currentIndex);
    
        // Check khoảng cách
        double distance = Math.sqrt(currentPos.getSquaredDistance(targetPos));
    
        if (distance < 2.0) {
            // Đã đến gần target
            player.sendMessage(Text.literal("§6[AutoGoto] Đã đến: " + 
                targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ()), false);
        
            // Di đến next target
            currentIndex++;
        
            if (currentIndex < coordinates.size()) {
                // Send goto command tiếp theo
                BlockPos nextTarget = coordinates.get(currentIndex);
                sendGotoCommand(player, nextTarget);
            
                player.sendMessage(Text.literal("§b[AutoGoto] Tiếp theo đi đến: " + 
                    nextTarget.getX() + ", " + nextTarget.getY() + ", " + nextTarget.getZ() + 
                    " (" + (currentIndex + 1) + "/" + coordinates.size() + ")"), false);
            } else {
                // Đã hoàn thành tất cả waypoints
                player.sendMessage(Text.literal("§a[AutoGoto] Hoàn thành vòng lặp " + (completedLoops + 1) + "!"), false);
            
                // Bắt đầu đợi 3 giây trước khi send custom message
                player.sendMessage(Text.literal("§b[AutoGoto] Đợi 3 giây trước khi send message..."), false);
                isWaitingForHome = true;  // Sử dụng lại biến này cho việc đợi
                homeDelayTicks = 0;
            }
        } else {
            // Update progress với thông tin loop
            String loopInfo = isLoopMode ? " [Vòng " + (completedLoops + 1) + "]" : "";
            player.sendMessage(Text.literal("§7[AutoGoto] Khoảng cách: " + 
                String.format("%.1f", distance) + " blocks (" + (currentIndex + 1) + "/" + coordinates.size() + ")" + loopInfo), true);
        }
    }
    
    // COMMAND HANDLERS
    private int startAutoGotoCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        startAutoGoto(player);
        return 1;
    }
    
    private int stopAutoGotoCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        stopAutoGoto(player);
        return 1;
    }
    
    private int setOnceModeCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        isLoopMode = false;
        saveSettings();
        player.sendMessage(Text.literal("§a[AutoGoto] Đã đặt chế độ: Chạy 1 lần"), false);
        return 1;
    }
    
    private int setLoopModeCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        isLoopMode = true;
        saveSettings();
        player.sendMessage(Text.literal("§a[AutoGoto] Đã đặt chế độ: Vòng lặp"), false);
        return 1;
    }
    
    private int showModeCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        String mode = isLoopMode ? "Vòng lặp" : "Chạy 1 lần";
        player.sendMessage(Text.literal("§e[AutoGoto] Chế độ hiện tại: " + mode), false);
        return 1;
    }
    
    private int setDelayCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        loopDelaySeconds = seconds;
        saveSettings();
        player.sendMessage(Text.literal("§a[AutoGoto] Đã đặt delay: " + seconds + " giây"), false);
        return 1;
    }
    
    private int showDelayCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        player.sendMessage(Text.literal("§e[AutoGoto] Delay hiện tại: " + loopDelaySeconds + " giây"), false);
        return 1;
    }
    
    private int showSettingsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        showSettings(player);
        return 1;
    }
    
    private int addPositionCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        addCurrentPosition(player);
        return 1;
    }
    
    private int clearPositionsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        clearAllPositions(player);
        return 1;
    }
    
    private int listPositionsCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        listAllPositions(player);
        return 1;
    }
    
    private int toggleAutoGotoCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        toggleAutoGoto(player);
        return 1;
    }
    
    private int setCustomMessageCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        String message = StringArgumentType.getString(context, "msg");
        customMessage = message;
        saveSettings();
        player.sendMessage(Text.literal("§a[AutoGoto] Đã đặt custom message: §7" + message), false);
        return 1;
    }
    
    private int showCustomMessageCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        player.sendMessage(Text.literal("§e[AutoGoto] Custom message hiện tại: §7" + customMessage), false);
        return 1;
    }
    
    private int showHelpCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        showHelp(player);
        return 1;
    }
    
    // CORE METHODS
    private void toggleAutoGoto(ClientPlayerEntity player) {
        if (isAutoGotoEnabled) {
            stopAutoGoto(player);
        } else {
            startAutoGoto(player);
        }
    }
    
    private void startAutoGoto(ClientPlayerEntity player) {
        if (coordinates.isEmpty()) {
            player.sendMessage(Text.literal("§c[AutoGoto] Chưa có vị trí nào! Dùng /autogoto add để thêm vị trí hiện tại"), false);
            return;
        }
        
        currentIndex = 0;
        isAutoGotoEnabled = true;
        isWaitingForNextLoop = false;
        isWaitingForHome = false;
        completedLoops = 0;
        loopDelayTicks = 0;
        homeDelayTicks = 0;
        
        BlockPos target = coordinates.get(currentIndex);
        String modeText = isLoopMode ? "vòng lặp" : "1 lần";
        player.sendMessage(Text.literal("§a[AutoGoto] Bắt đầu (chế độ: " + modeText + ")! Đi đến: " + 
            target.getX() + ", " + target.getY() + ", " + target.getZ() + 
            " (1/" + coordinates.size() + ")"), false);
        
        // Send command đầu tiên
        sendGotoCommand(player, target);
    }
    
    private void stopAutoGoto(ClientPlayerEntity player) {
        isAutoGotoEnabled = false;
        isWaitingForNextLoop = false;
        isWaitingForHome = false;
        currentIndex = 0;
        loopDelayTicks = 0;
        homeDelayTicks = 0;
        
        String completedText = completedLoops > 0 ? " (Đã hoàn thành " + completedLoops + " vòng lặp)" : "";
        player.sendMessage(Text.literal("§c[AutoGoto] Đã dừng!" + completedText), false);
        completedLoops = 0;
    }
    
    private void addCurrentPosition(ClientPlayerEntity player) {
        BlockPos currentPos = player.getBlockPos();
        coordinates.add(currentPos);
        
        // Auto-save after adding
        saveCurrentWaypoints();
        
        player.sendMessage(Text.literal("§a[AutoGoto] Đã thêm vị trí: " + 
            currentPos.getX() + ", " + currentPos.getY() + ", " + currentPos.getZ() + 
            " (Tổng: " + coordinates.size() + ")"), false);
    }
    
    private void clearAllPositions(ClientPlayerEntity player) {
        int count = coordinates.size();
        coordinates.clear();
        currentIndex = 0;
        isAutoGotoEnabled = false;
        isWaitingForNextLoop = false;
        isWaitingForHome = false;
        completedLoops = 0;
        
        // Auto-save after clearing
        saveCurrentWaypoints();
        
        player.sendMessage(Text.literal("§c[AutoGoto] Đã xóa " + count + " vị trí!"), false);
    }
    
    private void listAllPositions(ClientPlayerEntity player) {
        if (coordinates.isEmpty()) {
            player.sendMessage(Text.literal("§e[AutoGoto] Chưa có vị trí nào được lưu!"), false);
            return;
        }
        
        player.sendMessage(Text.literal("§e[AutoGoto] Danh sách vị trí (" + coordinates.size() + "):"), false);
        for (int i = 0; i < coordinates.size(); i++) {
            BlockPos pos = coordinates.get(i);
            String prefix = (i == currentIndex && isAutoGotoEnabled) ? "§a→ " : "§7  ";
            player.sendMessage(Text.literal(prefix + (i + 1) + ". " + 
                pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
        }
    }
    
    private void sendGotoCommand(ClientPlayerEntity player, BlockPos pos) {
        String command = "#goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        player.networkHandler.sendChatMessage(command);
        player.sendMessage(Text.literal("§e[AutoGoto] Đã gửi: " + command), false);
    }
    
    private void showSettings(ClientPlayerEntity player) {
        player.sendMessage(Text.literal("§e[AutoGoto] Cài đặt hiện tại:"), false);
        player.sendMessage(Text.literal("§7Chế độ: " + (isLoopMode ? "§aVòng lặp" : "§bChạy 1 lần")), false);
        player.sendMessage(Text.literal("§7Delay: §e" + loopDelaySeconds + " giây"), false);
        player.sendMessage(Text.literal("§7Custom message: §f" + customMessage), false);
        player.sendMessage(Text.literal("§7Waypoints: §e" + coordinates.size() + " vị trí"), false);
        if (isAutoGotoEnabled) {
            player.sendMessage(Text.literal("§7Trạng thái: §aĐang chạy (vòng " + (completedLoops + 1) + ")"), false);
        } else {
            player.sendMessage(Text.literal("§7Trạng thái: §cĐã dừng"), false);
        }
    }
    
    private void showHelp(ClientPlayerEntity player) {
        player.sendMessage(Text.literal("§e[AutoGoto] Danh sách lệnh:"), false);
        player.sendMessage(Text.literal("§7/autogoto add §f- Thêm vị trí hiện tại"), false);
        player.sendMessage(Text.literal("§7/autogoto list §f- Xem danh sách vị trí"), false);
        player.sendMessage(Text.literal("§7/autogoto start §f- Bắt đầu (hoặc nhấn G)"), false);
        player.sendMessage(Text.literal("§7/autogoto stop §f- Dừng (hoặc nhấn G)"), false);
        player.sendMessage(Text.literal("§7/autogoto clear §f- Xóa tất cả vị trí"), false);
        player.sendMessage(Text.literal("§7/autogoto mode once §f- Chế độ chạy 1 lần"), false);
        player.sendMessage(Text.literal("§7/autogoto mode loop §f- Chế độ vòng lặp"), false);
        player.sendMessage(Text.literal("§7/autogoto delay <giây> §f- Đặt delay giữa các vòng lặp"), false);
        player.sendMessage(Text.literal("§7/autogoto message <msg> §f- Đặt custom message"), false);
        player.sendMessage(Text.literal("§7/autogoto settings §f- Xem tất cả cài đặt"), false);
        player.sendMessage(Text.literal("§6=== FILE MANAGEMENT ==="), false);
        player.sendMessage(Text.literal("§7/autogoto save [name] §f- Lưu waypoints"), false);
        player.sendMessage(Text.literal("§7/autogoto load <name> §f- Load waypoints"), false);
        player.sendMessage(Text.literal("§7/autogoto delete <name> §f- Xóa file waypoints"), false);
        player.sendMessage(Text.literal("§7/autogoto files §f- Xem danh sách file"), false);
        player.sendMessage(Text.literal("§7/autogoto export <name> §f- Export để chia sẻ"), false);
        player.sendMessage(Text.literal("§7/autogoto import <name> §f- Import từ file chia sẻ"), false);
    }
}