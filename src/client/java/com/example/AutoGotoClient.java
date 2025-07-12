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
    
    @Override
    public void onInitializeClient() {
        // Tạo key binding
        toggleAutoGotoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autogoto.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.autogoto"
        ));
        
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
                .executes(this::showHelpCommand));
        });
        
        // Đăng ký client tick event
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        
        System.out.println("AutoGoto loaded with loop mode!");
    }
    
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
    
    // Command handlers
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
        player.sendMessage(Text.literal("§a[AutoGoto] Đã đặt chế độ: Chạy 1 lần"), false);
        return 1;
    }
    
    private int setLoopModeCommand(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        isLoopMode = true;
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
    
    // Core methods
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
    }
}