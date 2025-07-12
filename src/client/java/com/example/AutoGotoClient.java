package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class AutoGotoClient implements ClientModInitializer {
    private static KeyBinding autoGotoKey;
    private static KeyBinding addPositionKey;
    private static KeyBinding clearPositionsKey;
    private static KeyBinding listPositionsKey;
    
    private final List<BlockPos> coordinates = new ArrayList<>();
    
    private int currentIndex = 0;
    private boolean isAutoGotoEnabled = false;
    private int checkInterval = 0;
    
    @Override
    public void onInitializeClient() {
        // Tạo các keybinding
        autoGotoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autogoto.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.autogoto"
        ));
        
        addPositionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autogoto.add_position",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "category.autogoto"
        ));
        
        clearPositionsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autogoto.clear_positions",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "category.autogoto"
        ));
        
        listPositionsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autogoto.list_positions",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "category.autogoto"
        ));
        
        // Đăng ký client tick event
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        
        System.out.println("Loaded :))");
    }
    
    private void onClientTick(MinecraftClient client) {
        if (client.player == null) return;
        
        ClientPlayerEntity player = client.player;
        
        // Check các keybinding
        if (autoGotoKey.wasPressed()) {
            toggleAutoGoto(player);
        }
        
        if (addPositionKey.wasPressed()) {
            addCurrentPosition(player);
        }
        
        if (clearPositionsKey.wasPressed()) {
            clearAllPositions(player);
        }
        
        if (listPositionsKey.wasPressed()) {
            listAllPositions(player);
        }
        
        // Auto goto logic
        if (isAutoGotoEnabled && currentIndex < coordinates.size()) {
            checkPositionAndSendMessage(player);
        }
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
    
    private void toggleAutoGoto(ClientPlayerEntity player) {
        if (isAutoGotoEnabled) {
            stopAutoGoto(player);
        } else {
            startAutoGoto(player);
        }
    }
    
    private void startAutoGoto(ClientPlayerEntity player) {
        if (coordinates.isEmpty()) {
            player.sendMessage(Text.literal("§c[AutoGoto] Chưa có vị trí nào! Nhấn H để thêm vị trí hiện tại"), false);
            return;
        }
        
        currentIndex = 0;
        isAutoGotoEnabled = true;
        
        BlockPos target = coordinates.get(currentIndex);
        player.sendMessage(Text.literal("§a[AutoGoto] Bắt đầu! Đi đến: " + 
            target.getX() + ", " + target.getY() + ", " + target.getZ() + 
            " (1/" + coordinates.size() + ")"), false);
        
        // Send command dau tien
        sendGotoCommand(player, target);
    }
    
    private void stopAutoGoto(ClientPlayerEntity player) {
        isAutoGotoEnabled = false;
        currentIndex = 0;
        
        player.sendMessage(Text.literal("§c[AutoGoto] Đã dừng!"), false);
    }
    
    private void checkPositionAndSendMessage(ClientPlayerEntity player) {
        checkInterval++;
        if (checkInterval < 20) return; // Check mỗi giây (20 ticks)
        checkInterval = 0;
        
        BlockPos currentPos = player.getBlockPos();
        BlockPos targetPos = coordinates.get(currentIndex);
        
        // Check khoang cach
        double distance = Math.sqrt(currentPos.getSquaredDistance(targetPos));
        
        if (distance < 2.0) {
            // Đã đến gần target
            player.sendMessage(Text.literal("§6[AutoGoto] Đã đến: " + 
                targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ()), false);
            
            // Di den next target
            currentIndex++;
            
            if (currentIndex < coordinates.size()) {
                // send goto command tiep theo
                BlockPos nextTarget = coordinates.get(currentIndex);
                sendGotoCommand(player, nextTarget);
                
                player.sendMessage(Text.literal("§b[AutoGoto] Tiếp theo đi đến: " + 
                    nextTarget.getX() + ", " + nextTarget.getY() + ", " + nextTarget.getZ() + 
                    " (" + (currentIndex + 1) + "/" + coordinates.size() + ")"), false);
            } else {
                // Tat ca da xong
                player.sendMessage(Text.literal("§a[AutoGoto] Hoàn thành tất cả " + coordinates.size() + " vị trí!"), false);
                stopAutoGoto(player);
            }
        } else {
            player.sendMessage(Text.literal("§7[AutoGoto] Khoảng cách: " + 
                String.format("%.1f", distance) + " blocks (" + (currentIndex + 1) + "/" + coordinates.size() + ")"), true);
        }
    }
    
    private void sendGotoCommand(ClientPlayerEntity player, BlockPos pos) {
        String command = "#goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        player.networkHandler.sendChatMessage(command);
        player.sendMessage(Text.literal("§e[AutoGoto] Đã gửi: " + command), false);
    }
}