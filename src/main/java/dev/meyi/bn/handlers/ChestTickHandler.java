package dev.meyi.bn.handlers;

import dev.meyi.bn.BazaarNotifier;
import dev.meyi.bn.json.Order;
import dev.meyi.bn.modules.calc.BankCalculator;
import dev.meyi.bn.utilities.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StringUtils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ChestTickHandler {

  public static String lastScreenDisplayName = "";
  private static long date = System.currentTimeMillis();

  // /blockdata x y z {CustomName:"___"} << For Custom Chest Name Testing

  public static void updateBazaarOrders(IInventory chest) {
    int[] verifiedOrders = new int[BazaarNotifier.orders.size()];
    ItemStack[] items = new ItemStack[chest.getSizeInventory() + 1];
    for (int i = 0; i < chest.getSizeInventory(); i++) {
      items[i] = chest.getStackInSlot(i);
    }
    items[items.length - 1] = Minecraft.getMinecraft().thePlayer.inventory.getItemStack();

    for (ItemStack item : items) {
      if (item != null
          && Item.itemRegistry.getIDForObject(item.getItem()) != 160    // Glass
          && Item.itemRegistry.getIDForObject(item.getItem()) != 102    // Glass
          && Item.itemRegistry.getIDForObject(item.getItem()) != 262) { // Arrow
        NBTTagList lorePreFilter = item.getTagCompound()
            .getCompoundTag("display")
            .getTagList("Lore", 8);

        List<String> lore = new ArrayList<>();

        for (int j = 0; j < lorePreFilter.tagCount(); j++) {
          lore.add(StringUtils.stripControlCodes(lorePreFilter.getStringTagAt(j)));
        }

        Pattern p = Pattern
            .compile("(BUY|SELL):? (.*)");
        Matcher m = p.matcher(StringUtils.stripControlCodes(item.getDisplayName()));
        String displayName = "";
        String type = "";
        if (m.find()) {
          displayName = m.group(2);
          type = m.group(1).toLowerCase();
        } else {
          System.out.println("Bazaar item header incorrect. Aborting!");
          return;
        }

        if (BazaarNotifier.bazaarConv.containsValue(displayName)) {
          int amountLeft = -1;

          String priceString;
          if (lore.get(4).toLowerCase().contains("expire")) {
            priceString = StringUtils.stripControlCodes(lore.get(6)).replaceAll(",", "")
                .split(" ")[3];
          } else if (lore.get(5).toLowerCase().contains("expire")) {
            priceString = StringUtils.stripControlCodes(lore.get(7)).replaceAll(",", "")
                .split(" ")[3];
          } else {
            priceString = StringUtils.stripControlCodes(
                lore.get((lore.get(3).startsWith("Filled:")) ? 5 : 4).replaceAll(",", "")
                    .split(" ")[3]);
          }
          int orderInQuestion = -1;
          for (int j = 0; j < BazaarNotifier.orders.size(); j++) {
            Order order = BazaarNotifier.orders.get(j);
            if (priceString.equalsIgnoreCase(order.priceString) && type
                .equals(order.type)) {
              orderInQuestion = j;
              break;
            }
          }
          if (orderInQuestion != -1) {
            verifiedOrders[orderInQuestion] = 1;
            boolean forceRemove = false;
            int totalAmount = BazaarNotifier.orders.get(orderInQuestion).startAmount;
            if (lore.get(3).startsWith("Filled:")) {
              if (lore.get(3).split(" ")[2].equals("100%")) {
                amountLeft = 0;
              } else {
                int amountFulfilled = 0;
                for (int j = 8; j < lore.size(); j++) {
                  if (lore.get(j).isEmpty()) {
                    break;
                  } else if (lore.get(j).startsWith(" + ") && lore.get(j).endsWith("others")) {
                    forceRemove = true;
                    break;
                  }
                  amountFulfilled += Integer.parseInt(
                      lore.get(j).replaceAll(",", "").split("x ")[0].substring(2));
                }
                amountLeft = totalAmount - amountFulfilled;
              }
            }
            if (forceRemove) {
              if (BazaarNotifier.config.showChatMessages) {
                Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(
                    BazaarNotifier.prefix + EnumChatFormatting.RED
                        + "Because of the limitations of the bazaar's information, you had an order removed that exceeded the maximum number of buyers/sellers. If you want, you can cancel the missing order freely and put it back up."));
              }
              verifiedOrders[orderInQuestion] = 0;

            } else if (amountLeft > 0) {
              BazaarNotifier.orders.get(orderInQuestion).setAmountRemaining(amountLeft);
            }
          }
        } else {
          System.out.println(BazaarNotifier.orders);
          System.err.println("Some orders weren't found! Bad display name: " + displayName);
          return;
        }
      }
    }

    for (int i = verifiedOrders.length - 1; i >= 0; i--) {
      if (verifiedOrders[i] == 0) {
        BazaarNotifier.orders.remove(i);
      }
    }
  }

  @SubscribeEvent
  public void onChestTick(TickEvent e) {
    if (e.phase == Phase.END) {
      if (Minecraft.getMinecraft().currentScreen instanceof GuiChest && BazaarNotifier.inBazaar
          && BazaarNotifier.activeBazaar) {

        IInventory chest = ((GuiChest) Minecraft.getMinecraft().currentScreen).lowerChestInventory;
        String chestName = chest.getDisplayName().getUnformattedText().toLowerCase();

        if (chest.hasCustomName() && !lastScreenDisplayName.equalsIgnoreCase(chestName)) {
          if (chestName.equals("confirm buy order") ||
              chestName.equals("confirm sell offer")) {

            if (chest.getStackInSlot(13) != null) {
              lastScreenDisplayName = StringUtils
                  .stripControlCodes(chest.getDisplayName().getUnformattedText());
              orderConfirmation(chest);
            }

          } else if (chestName.contains("bazaar orders")) {
            if (chest.getStackInSlot(chest.getSizeInventory() - 5) != null
                && Item.itemRegistry
                .getIDForObject(chest.getStackInSlot(chest.getSizeInventory() - 5).getItem())
                == 262) {
              lastScreenDisplayName = StringUtils
                  .stripControlCodes(chest.getDisplayName().getUnformattedText());
              updateBazaarOrders(chest);
            }
          }
        }
      } else if (BazaarNotifier.inBank && Minecraft
          .getMinecraft().currentScreen instanceof GuiChest) {
        IInventory chest = ((GuiChest) Minecraft.getMinecraft().currentScreen).lowerChestInventory;
        String chestName = chest.getDisplayName().getUnformattedText().toLowerCase();
        if (chestName.contains("personal bank account") && !chestName.contains("upgrade")) {
          BankCalculator.extractBankFromItemDescription(
              ((GuiChest) Minecraft.getMinecraft().currentScreen).lowerChestInventory, false);
        } else if (chestName.contains("co-op bank account") && !chestName.contains("upgrade")) {
          BankCalculator.extractBankFromItemDescription(
              ((GuiChest) Minecraft.getMinecraft().currentScreen).lowerChestInventory, true);
        } else if (chestName.equals("bank deposit") || chestName.equals("bank withdrawal")) {
          BankCalculator.isOnDangerousPage = true;
          BankCalculator.purseInBank = BankCalculator.getPurse();
        }

      } else if (!BazaarNotifier.inBazaar) { // if you aren't in the bazaar, this should be clear
        ChestTickHandler.lastScreenDisplayName = "";
      }
    }
  }

  private void orderConfirmation(IInventory chest) {

    if (chest.getStackInSlot(13) != null) {

      String priceString = StringUtils.stripControlCodes(
          chest.getStackInSlot(13).getTagCompound().getCompoundTag("display").getTagList("Lore", 8)
              .getStringTagAt(2)).split(" ")[3].replaceAll(",", "");
      double price = Double.parseDouble(priceString);

      String product = StringUtils.stripControlCodes(
          chest.getStackInSlot(13).getTagCompound().getCompoundTag("display")
              .getTagList("Lore", 8).getStringTagAt(4)).split("x ")[1];

      if (!BazaarNotifier.bazaarConv.containsValue(product)) {
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(
            BazaarNotifier.prefix + EnumChatFormatting.RED
                + "The bazaar item you just put an order for doesn't exist. Please report this in the discord server"));
        if (System.currentTimeMillis() > date + (60 * 60 * 1000)) {
          try {
            Utils.updateResources();
          } catch (IOException ignored) {
          }
        }
        date = System.currentTimeMillis();
      } else {
        String productName = BazaarNotifier.bazaarConv.inverse()
            .get(product);
        String productWithAmount = StringUtils.stripControlCodes(
            chest.getStackInSlot(13).getTagCompound().getCompoundTag("display")
                .getTagList("Lore", 8).getStringTagAt(4)).split(": ")[1];

        int amount = Integer.parseInt(StringUtils.stripControlCodes(
            chest.getStackInSlot(13).getTagCompound().getCompoundTag("display")
                .getTagList("Lore", 8).getStringTagAt(4)).split(": ")[1].split("x ")[0]
            .replaceAll(",", ""));

        EventHandler.productVerify[0] = productName;
        EventHandler.productVerify[1] = productWithAmount;
        String type = StringUtils.stripControlCodes(chest.getDisplayName().getUnformattedText())
            .equalsIgnoreCase("Confirm Sell Offer") ? "sell" : "buy";
        EventHandler.verify = new Order(product, amount, price, priceString, type);
      }
    }
  }
}