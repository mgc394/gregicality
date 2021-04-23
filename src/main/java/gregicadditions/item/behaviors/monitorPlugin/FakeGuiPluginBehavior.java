package gregicadditions.item.behaviors.monitorPlugin;

import gregicadditions.item.behaviors.monitorPlugin.fakegui.FakeEntityPlayer;
import gregicadditions.item.behaviors.monitorPlugin.fakegui.FakeModularGui;
import gregicadditions.item.behaviors.monitorPlugin.fakegui.FakeModularUIContainer;
import gregicadditions.utils.BlockPatternChecker;
import gregicadditions.utils.GALog;
import gregicadditions.utils.Tuple;
import gregicadditions.widgets.monitor.WidgetPluginConfig;
import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.IUIHolder;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.Widget;
import gregtech.api.gui.widgets.*;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.multiblock.BlockPattern;
import gregtech.api.multiblock.PatternMatchContext;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

import java.lang.reflect.Method;
import java.util.*;

public class FakeGuiPluginBehavior extends ProxyHolderPluginBehavior {

    private int partIndex;

    //run-time
    @SideOnly(Side.CLIENT)
    private FakeModularGui fakeModularGui;
    private BlockPos partPos;
    private FakeModularUIContainer fakeModularUIContainer;
    private FakeEntityPlayer fakePlayer;
    private static final Method methodCreateUI = ObfuscationReflectionHelper.findMethod(MetaTileEntity.class, "createUI", ModularUI.class, EntityPlayer.class);
    static{
        methodCreateUI.setAccessible(true);
    }

    public void setConfig(int partIndex) {
        if(this.partIndex == partIndex || partIndex < 0) return;
        this.partIndex = partIndex;
        this.partPos = null;
        writePluginData(1, buffer -> {
            buffer.writeVarInt(this.partIndex);
        });
        markDirty();
    }

    public MetaTileEntity getRealMTE() {
        MetaTileEntity target = this.holder.getMetaTileEntity();
        if (target instanceof MultiblockControllerBase && partIndex > 0) {
            if (partPos != null) {
                TileEntity entity = this.screen.getWorld().getTileEntity(partPos);
                if (entity instanceof MetaTileEntityHolder) {
                    return ((MetaTileEntityHolder) entity).getMetaTileEntity();
                } else {
                    partPos = null;
                    return null;
                }
            }
            PatternMatchContext context = BlockPatternChecker.checkPatternAt((MultiblockControllerBase) target);
            if (context == null) {
                return null;
            }
            Set<IMultiblockPart> rawPartsSet = context.getOrCreate("MultiblockParts", HashSet::new);
            List<IMultiblockPart> parts = new ArrayList<>(rawPartsSet);
            parts.sort(Comparator.comparing((it) -> ((MetaTileEntity)it).getPos().hashCode()));
            if (parts.size() > partIndex - 1 && parts.get(partIndex - 1) instanceof MetaTileEntity) {
                target = (MetaTileEntity) parts.get(partIndex - 1);
                partPos = target.getPos();
            } else {
                return null;
            }
        }
        return target;
    }

    public void createFakeGui() {
        if (this.holder == null || this.screen == null || !this.screen.isValid()) return;
        try {
            fakePlayer = new FakeEntityPlayer(this.screen.getWorld());
            MetaTileEntity mte = getRealMTE();
            if (mte == null || (this.partIndex > 0 && this.holder.getMetaTileEntity() == mte)) {
                fakeModularUIContainer = null;
                if (this.screen.getWorld().isRemote) {
                    fakeModularGui = null;
                }
                return;
            }
            ModularUI ui = (ModularUI) methodCreateUI.invoke(mte, fakePlayer);
            if (ui == null) {
                fakeModularUIContainer = null;
                if (this.screen.getWorld().isRemote) {
                    fakeModularGui = null;
                }
                return;
            }
            List<Widget> widgets = new ArrayList<>();
            boolean hasPlayerInventory = false;
            for (Widget widget : ui.guiWidgets.values()) {
                if (widget instanceof SlotWidget) {
                    SlotItemHandler handler = ((SlotWidget) widget).getHandle();
                    if (handler.getItemHandler() instanceof PlayerMainInvWrapper) {
                        hasPlayerInventory = true;
                        continue;
                    }
                }
                widgets.add(widget);
            }
            ModularUI.Builder builder = new ModularUI.Builder(ui.backgroundPath, ui.getWidth(), ui.getHeight() - (hasPlayerInventory? 80:0));
            for (Widget widget : widgets) {
                builder.widget(widget);
            }
            ui = builder.build(ui.holder, ui.entityPlayer);
            fakeModularUIContainer = new FakeModularUIContainer(ui, this);
            if (this.screen.getWorld().isRemote) {
                fakeModularGui = new FakeModularGui(ui, fakeModularUIContainer);
                writePluginAction(0, buffer -> {});
            }
        } catch (Exception e) {
            GALog.logger.error(e);
        }
    }

    @Override
    public void readPluginAction(EntityPlayerMP player, int id, PacketBuffer buf) {
        super.readPluginAction(player, id, buf);
        if (id == 0) {
            createFakeGui();
        }
        if (id == 1) {
            if (this.fakeModularUIContainer != null) {
                fakeModularUIContainer.handleClientAction(buf);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setInteger("part", partIndex);
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        partIndex = data.hasKey("part")? data.getInteger("part"):0;
    }

    @Override
    public void onHolderChanged(MetaTileEntityHolder lastHolder) {
        if (holder == null) {
            if (this.screen.getWorld() != null && this.screen.getWorld().isRemote) {
                fakeModularGui = null;
            }
            fakeModularUIContainer = null;
            fakePlayer = null;
        } else {
            if (this.screen.getWorld().isRemote) {
                createFakeGui();
            }
        }
    }

    @Override
    public void update() {
        super.update();
        if (this.screen.getWorld().isRemote) {
            if (partIndex > 0 && fakeModularUIContainer == null && this.screen.getTimer() % 20 == 0) {
                createFakeGui();
            }
            if (fakeModularGui != null)
                fakeModularGui.updateScreen();
        } else {
            if (partIndex > 0 && this.screen.getTimer() % 20 == 0) {
                if (fakeModularUIContainer != null && getRealMTE() == null) {
                    this.writePluginData(1, buf->{
                        buf.writeVarInt(this.partIndex);
                    });
                    fakeModularUIContainer = null;
                }
            }
            if (fakeModularUIContainer != null)
                fakeModularUIContainer.detectAndSendChanges();
        }
    }

    @Override
    public MonitorPluginBaseBehavior createPlugin() {
        return new FakeGuiPluginBehavior();
    }

    @Override
    public void renderPlugin(float partialTicks) {
        if (fakeModularGui != null) {
            Tuple<Double, Double> result = this.screen.checkLookingAt(partialTicks);
            if (result == null)
                fakeModularGui.drawScreen(0, 0, partialTicks);
            else
                fakeModularGui.drawScreen(result.getKey(), result.getValue(), partialTicks);
        }
    }

    @Override
    public boolean onClickLogic(EntityPlayer playerIn, EnumHand hand, EnumFacing facing, boolean isRight, double x, double y) {
        if (this.screen.getWorld().isRemote) return true;
        if (fakeModularUIContainer != null && fakeModularUIContainer.modularUI != null && !playerIn.getHeldItemMainhand().hasCapability(GregtechCapabilities.CAPABILITY_SCREWDRIVER, null)) {
            int width = fakeModularUIContainer.modularUI.getWidth();
            int height = fakeModularUIContainer.modularUI.getHeight();
            float halfW = width / 2f;
            float halfH = height / 2f;
            float scale = 0.5f / Math.max(halfW, halfH);
            int mouseX = (int) ((x / scale) + (halfW > halfH? 0: (halfW - halfH)));
            int mouseY = (int) ((y / scale) + (halfH > halfW? 0: (halfH - halfW)));
            MetaTileEntity mte = getRealMTE();
            if (mte != null && 0 <= mouseX && mouseX <= width && 0 <= mouseY&& mouseY <= height) {
                if (playerIn.isSneaking()) {
                    writePluginData(-2, buf->{
                        buf.writeVarInt(mouseX);
                        buf.writeVarInt(mouseY);
                        buf.writeVarInt(isRight?1:0);
                        buf.writeVarInt(fakeModularUIContainer.syncId);
                    });
                } else {
                    return isRight && mte.onRightClick(playerIn, hand, facing, null) || super.onClickLogic(playerIn, hand, facing, isRight, x, y);
                }
            }
        }
        return super.onClickLogic(playerIn, hand, facing, isRight, x, y);
    }

    @Override
    public void readPluginData(int id, PacketBuffer buf) {
        super.readPluginData(id, buf);
        if (id == 1) {
            this.partIndex = buf.readVarInt();
            this.partPos = null;
            createFakeGui();
        }
        else if (id == 0) {
            int windowID = buf.readVarInt();
            int widgetID = buf.readVarInt();
            if (fakeModularGui != null)
                fakeModularGui.handleWidgetUpdate(windowID, widgetID, buf);
        } else if (id == -1) {
            if (fakeModularUIContainer != null)
                fakeModularUIContainer.handleSlotUpdate(buf);
        } else if (id == -2) {
            int mouseX = buf.readVarInt();
            int mouseY = buf.readVarInt();
            int button = buf.readVarInt();
            int syncID = buf.readVarInt();
            if (fakeModularGui != null &&  fakeModularUIContainer != null) {
                fakeModularUIContainer.syncId = syncID;
                fakeModularGui.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    @Override
    public WidgetPluginConfig customUI(WidgetPluginConfig widgetGroup, IUIHolder holder, EntityPlayer entityPlayer) {
        return widgetGroup.setSize(170, 50)
                .widget(new LabelWidget(20, 20, "Part:", 0xFFFFFFFF))
                .widget(new ClickButtonWidget(55, 15, 20, 20, "-1", (data) -> setConfig(this.partIndex - 1)))
                .widget(new ClickButtonWidget(135, 15, 20, 20, "+1", (data) -> setConfig(this.partIndex + 1)))
                .widget(new ImageWidget(75, 15, 60, 20, GuiTextures.DISPLAY))
                .widget(new SimpleTextWidget(105, 25, "", 16777215, () -> Integer.toString(this.partIndex)));
    }
}
