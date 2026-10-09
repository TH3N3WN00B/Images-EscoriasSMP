package com.andavin.images.command;

import com.andavin.images.Images;
import com.andavin.images.image.CustomImage;
import com.andavin.util.Scheduler;

import static com.andavin.images.image.CustomImage.UNKNOWN_CREATOR;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * @since July 05, 2020
 * @author Andavin
 */
final class DeleteNearCommand extends BaseCommand {

    DeleteNearCommand() {
        super("near", "images.command.delete.near");
        this.setAliases("n");
        this.setMinimumArgs(1);
        this.setUsage("/image delete near <range>");
        this.setDesc("Delete all images within a specified range");
    }

    @Override
    public void execute(Player player, String label, String[] args) {

        int range;
        try {
            range = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid range: §f" + args[0]);
            return;
        }

        if (range < 1) {
            player.sendMessage("§cRange must be at least 1");
            return;
        }

        if (range > 30000) { // Larger values overflow the squared distance
            player.sendMessage("§cRange must be at most 30000");
            return;
        }

        // Respect the same restriction as deleting a single image
        boolean restricted = Images.getInstance().getConfig().getBoolean("permissions.creator-restricted") &&
                !player.hasPermission("images.restricted.bypass");
        Location location = player.getLocation();
        List<CustomImage> images = Images.getMatchingImages(image -> image.isInRange(location, range) &&
                (!restricted || image.getCreator().equals(UNKNOWN_CREATOR) ||
                        image.getCreator().equals(player.getUniqueId())));
        if (images.isEmpty()) {
            player.sendMessage("§cNo images found in range of §f" + range);
            return;
        }

        // Removal touches the database so keep it off of the main thread
        Scheduler.async(() -> {

            int success = 0;
            for (CustomImage image : images) {

                if (Images.removeImage(image)) {
                    image.destroy();
                    success++;
                }
            }

            if (success == images.size()) {
                player.sendMessage("§aSuccessfully deleted §f" + success + "§a images within §f" + range + " blocks");
            } else {
                player.sendMessage("§cFound §f" + images.size() + "§c nearby images");
                if (success > 0) {
                    player.sendMessage("§cbut only §f" + success + "§c were successfully deleted");
                } else {
                    player.sendMessage("§cbut there was an issue deleting them");
                }
            }
        });
    }
}
