/*
 * MIT License
 *
 * Copyright (c) 2020 Mark
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.andavin.images;

import com.andavin.images.PacketListener.Hand;
import com.andavin.images.PacketListener.ImageListener;
import com.andavin.images.PacketListener.InteractType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract;
import com.github.retrooper.packetevents.event.simple.PacketPlayReceiveEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPickItemFromEntity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * @since May 10, 2026
 * @author Andavin
 */
class PacketEventsListener extends SimplePacketListenerAbstract {

    private final ImageListener listener;
    private final PacketListener packetListener;

    PacketEventsListener(ImageListener listener, PacketListener packetListener) {
        super(PacketListenerPriority.NORMAL);
        this.listener = listener;
        this.packetListener = packetListener;
    }

    @Override
    public void onPacketPlayReceive(PacketPlayReceiveEvent event) {

        PacketType.Play.Client type = event.getPacketType();
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            Player player = event.getPlayer();
            boolean attack = wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
            Hand hand = wrapper.getHand() == InteractionHand.OFF_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
            PacketListener.call(player, wrapper.getEntityId(),
                    attack ? InteractType.LEFT_CLICK : InteractType.RIGHT_CLICK, hand, this.listener);
        } else if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            WrapperPlayClientCreativeInventoryAction wrapper = new WrapperPlayClientCreativeInventoryAction(event);
            this.packetListener.pickItem(event.getPlayer(), -1, mapId(wrapper.getItemStack()));
        } else if (type == PacketType.Play.Client.PICK_ITEM_FROM_ENTITY) {
            WrapperPlayClientPickItemFromEntity wrapper = new WrapperPlayClientPickItemFromEntity(event);
            this.packetListener.pickItem(event.getPlayer(), wrapper.getEntityId(), -1);
        }
    }

    /**
     * Register the packet listener with PacketEvents.
     * <p>
     * This is here only because it cannot be in the main plugin
     * class since the server initializes all references regardless
     * of if they are ever reached during runtime.
     *
     * @param listenerTasks The listener tasks.
     * @param bridge The bridge {@link PacketListener}
     */
    static void register(Map<UUID, ImageListener> listenerTasks, PacketListener bridge) {
        PacketEvents.getAPI().getEventManager().registerListener(
                new PacketEventsListener((clicker, image, section, action, hand) -> {

                    ImageListener listener = listenerTasks.remove(clicker.getUniqueId());
                    if (listener != null) {
                        listener.click(clicker, image, section, action, hand);
                    }
                }, bridge));
    }

    /**
     * Extract the map ID from the {@link ItemStack} in the packet
     * depending on how the map ID is stored for the current era:
     * <ul>
     *     <li>1.20.5+ stores it in the {@code minecraft:map_id} data component.</li>
     *     <li>1.13 - 1.20.4 stores it in the {@code map} NBT tag.</li>
     *     <li>1.12 and below stores it as the old item damage/legacy data.</li>
     * </ul>
     *
     * @param item The item stack to extract the map ID from.
     * @return The map ID or {@code -1} if it could not be found.
     */
    private static int mapId(ItemStack item) {

        if (item.hasComponent(ComponentTypes.MAP_ID)) {
            Integer id = item.getComponent(ComponentTypes.MAP_ID).orElse(null);
            if (id != null) {
                return id;
            }
        }

        NBTCompound nbt = item.getNBT();
        if (nbt != null) {
            Number map = nbt.getNumberTagValueOrNull("map");
            if (map != null) {
                return map.intValue();
            }
        }

        return item.getLegacyData();
    }
}