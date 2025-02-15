package gregicadditions.machines.multi.centralmonitor;

import codechicken.lib.raytracer.CuboidRayTraceResult;
import gregicadditions.client.ClientHandler;
import gregicadditions.covers.CoverDigitalInterface;
import gregicadditions.item.behaviors.monitorPlugin.ProxyHolderPluginBehavior;
import gregicadditions.item.behaviors.monitorPlugin.MonitorPluginBaseBehavior;
import gregicadditions.machines.GATileEntities;
import gregicadditions.renderer.RenderHelper;
import gregicadditions.utils.BlockPatternChecker;
import gregicadditions.utils.Tuple;
import gregicadditions.widgets.WidgetARGB;
import gregicadditions.widgets.monitor.WidgetMonitorScreen;
import gregicadditions.widgets.monitor.WidgetPluginConfig;
import gregicadditions.widgets.monitor.WidgetCoverList;
import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.cover.CoverBehavior;
import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.widgets.*;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.MetaTileEntityUIFactory;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.common.metatileentities.electric.multiblockpart.MetaTileEntityMultiblockPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

public class MetaTileEntityMonitorScreen extends MetaTileEntityMultiblockPart {

    // run-time data
    public CoverDigitalInterface coverTMP;
    private long lastClickTime;
    private UUID lastClickUUID;
    public MonitorPluginBaseBehavior plugin;
    // persistent data
    public Tuple<BlockPos, EnumFacing> coverPos;
    public CoverDigitalInterface.MODE mode = CoverDigitalInterface.MODE.FLUID;
    public int slot = 0;
    public float scale = 1;
    public int frameColor = 0XFF00FF00;
    private ItemStackHandler inventory;

    public MetaTileEntityMonitorScreen(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, 1);
    }

    public void setMode(Tuple<BlockPos, EnumFacing> cover, CoverDigitalInterface.MODE mode) {
        CoverDigitalInterface last_cover = this.getCoverFromPosSide(coverPos);
        CoverDigitalInterface now_cover = this.getCoverFromPosSide(cover);
        if (this.mode == mode) {
            if (Objects.equals(cover, coverPos) && last_cover == null && cover == null || last_cover != null && last_cover == now_cover){
                return;
            }
        }
        if (last_cover != null && this.mode != CoverDigitalInterface.MODE.PROXY) {
            last_cover.unSubProxyMode(this.mode);
        }
        if (cover != null && mode != CoverDigitalInterface.MODE.PROXY) {
            now_cover.subProxyMode(mode);
        }
        this.coverPos = cover;
        this.mode = mode;
        updateProxyPlugin();
        writeCustomData(1, this::writeSync);
        this.markDirty();
    }

    public void setMode(Tuple<BlockPos, EnumFacing> cover) {
        setMode(cover, this.mode);
    }

    public void setMode(CoverDigitalInterface.MODE mode) {
        this.setMode(this.coverPos, mode);
    }

    public void setConfig(int slot, float scale, int color) {
        if ((this.scale == scale || scale < 1 || scale > 8) && (this.slot == slot || slot < 0) && this.frameColor == color) return;
        this.slot = slot;
        this.scale = scale;
        this.frameColor = color;
        writeCustomData(1, this::writeSync);
        markDirty();
    }

    public CoverDigitalInterface getCoverFromPosSide(Tuple<BlockPos, EnumFacing> posFacing) {
        if (posFacing == null) return null;
        MetaTileEntityHolder holder = getHolderFromPos(posFacing.getKey());
        if (holder == null) return null;
        CoverBehavior cover = holder.getMetaTileEntity().getCoverAtSide(posFacing.getValue());
        if (cover instanceof CoverDigitalInterface){
            return (CoverDigitalInterface) cover;
        }
        return null;
    }

    public MetaTileEntityHolder getHolderFromPos(BlockPos pos) {
        TileEntity te = this.getWorld() == null || pos == null? null:this.getWorld().getTileEntity(pos);
        if (te instanceof MetaTileEntityHolder && ((MetaTileEntityHolder) te).isValid()) {
            return (MetaTileEntityHolder) te;
        }
        return null;
    }

    public void updateCoverValid(List<Tuple<BlockPos, EnumFacing>> covers) {
        if (this.coverPos != null) {
            if (!covers.contains(this.coverPos)) {
                setMode(null, this.mode);
            }
        }
    }

    private void writeSync(PacketBuffer buf) {
        buf.writeBoolean(this.coverPos != null);
        if (this.coverPos != null) {
            buf.writeBlockPos(coverPos.getKey());
            buf.writeByte(coverPos.getValue().getIndex());
        }
        buf.writeByte(this.mode.ordinal());
        buf.writeVarInt(this.slot);
        buf.writeFloat(this.scale);
        buf.writeVarInt(this.frameColor);
    }

    private void readSync(PacketBuffer buf) {
        if (buf.readBoolean()) {
            BlockPos pos = buf.readBlockPos();
            EnumFacing side = EnumFacing.byIndex(buf.readByte());
            Tuple<BlockPos, EnumFacing> pair = new Tuple<>(pos, side);
            if (!pair.equals(this.coverPos)) {
                this.coverTMP = null;
                this.coverPos = pair;
            }
        } else {
            this.coverPos = null;
            this.coverTMP = null;
        }
        this.mode = CoverDigitalInterface.MODE.VALUES[buf.readByte()];
        this.slot = buf.readVarInt();
        float scale = buf.readFloat();
        this.scale = scale;
        this.frameColor = buf.readVarInt();
        updateProxyPlugin();
    }

    private void updateProxyPlugin() {
        if (this.plugin instanceof ProxyHolderPluginBehavior) {
            if (this.mode == CoverDigitalInterface.MODE.PROXY && coverPos != null) {
                ((ProxyHolderPluginBehavior) this.plugin).onHolderPosUpdated(coverPos.getKey());
            } else {
                ((ProxyHolderPluginBehavior) this.plugin).onHolderPosUpdated(null);
            }
        }
    }

    public int getX() {
        MultiblockControllerBase controller = this.getController();
        if (controller != null) {
            EnumFacing spin = BlockPatternChecker.getSpin(controller);
            switch (controller.getFrontFacing().getAxis()) {
                case Y:
                    if (spin.getAxis() == EnumFacing.Axis.X)
                        return Math.abs(this.getController().getPos().getZ() - this.getPos().getZ()) - 1;
                    else
                        return Math.abs(this.getController().getPos().getX() - this.getPos().getX()) - 1;
                case X:
                    if (spin.getAxis() == EnumFacing.Axis.Z)
                        return Math.abs(this.getController().getPos().getZ() - this.getPos().getZ()) - 1;
                    else
                        return Math.abs(this.getController().getPos().getY() - this.getPos().getY()) - 1;
                default:
                    if (spin.getAxis() == EnumFacing.Axis.Z)
                        return Math.abs(this.getController().getPos().getX() - this.getPos().getX()) - 1;
                    else
                        return Math.abs(this.getController().getPos().getY() - this.getPos().getY()) - 1;
            }
        }
        return -1;
    }

    public int getY() {
        MultiblockControllerBase controller = this.getController();
        if (controller != null) {
            EnumFacing spin = BlockPatternChecker.getSpin(controller);
            EnumFacing facing = controller.getFrontFacing();
            int height = ((MetaTileEntityCentralMonitor)this.getController()).height;
            switch (facing.getAxis()) {
                case Y:
                    if (spin.getAxis() == EnumFacing.Axis.X)
                        return height - (Math.abs(controller.getPos().getX() - spin.getXOffset() - this.getPos().getX())) - 1;
                    else
                        return height - (Math.abs(controller.getPos().getZ() - spin.getZOffset() - this.getPos().getZ())) - 1;
                case X:
                    if (spin.getAxis() == EnumFacing.Axis.Z)
                        return height - (Math.abs(controller.getPos().getY() + spin.getZOffset() - this.getPos().getY())) - 1;
                    else
                        return height - (Math.abs(controller.getPos().getZ() + spin.getXOffset() * facing.rotateY().getZOffset() - this.getPos().getZ())) - 1;
                default:
                    if (spin.getAxis() == EnumFacing.Axis.Z)
                        return height - (Math.abs(controller.getPos().getY() + spin.getZOffset() - this.getPos().getY())) - 1;
                    else
                        return height - (Math.abs(controller.getPos().getX() + spin.getXOffset() * facing.rotateY().getXOffset() - this.getPos().getX())) - 1;
            }
        }
        return -1;
    }

    public boolean isActive() {
        if (this.coverPos != null && this.mode != CoverDigitalInterface.MODE.PROXY) {
            CoverDigitalInterface cover = coverTMP != null? coverTMP : this.getCoverFromPosSide(this.coverPos);
            if (cover != null) {
                if (cover.isValid() && cover.isProxy()) {
                    coverTMP = cover;
                    return true;
                }
            }
            this.coverPos = null;
        }
        return plugin != null;
    }

    public void pluginDirty() {
        if (plugin != null) {
            plugin.writeToNBT(this.itemInventory.getStackInSlot(0).getOrCreateSubCompound("monitor_plugin"));
        }
    }

    private void loadPlugin(MonitorPluginBaseBehavior plugin) {
        if (this.plugin == null) {
            this.plugin = plugin.createPlugin();
            this.plugin.readFromNBT(this.itemInventory.getStackInSlot(0).getOrCreateSubCompound("monitor_plugin"));
            this.plugin.onMonitorValid(this, true);
        }
        updateProxyPlugin();
    }

    private void unloadPlugin() {
        if (plugin != null) {
            this.plugin.onMonitorValid(null, false);
        }
        this.plugin = null;
    }

    @Override
    public void update() {
        super.update();
        if(plugin != null && this.getController() != null && this.isActive()) {
            plugin.update();
        }
    }

    @SideOnly(Side.CLIENT)
    public void renderScreen(float partialTicks) {
        EnumFacing side = getController().getFrontFacing();
        GlStateManager.translate((scale - 1) * 0.5, (scale - 1) * 0.5, 0);
        GlStateManager.scale(this.scale,this.scale,1);

        if (plugin != null) {
            plugin.renderPlugin(partialTicks);
        }

        if (coverTMP != null) {
            boolean flag = true;
            if(checkLookingAt(partialTicks) != null && plugin == null && this.mode != CoverDigitalInterface.MODE.PROXY) {
                EntityPlayer player = Minecraft.getMinecraft().player;
                RayTraceResult rayTraceResult = player.rayTrace(Minecraft.getMinecraft().playerController.getBlockReachDistance(), partialTicks);
                if(coverTMP.renderSneakingLookAt(rayTraceResult.getBlockPos(), side, slot, partialTicks)) {
                    flag = false;
                }
            }
            if (this.mode == CoverDigitalInterface.MODE.PROXY) return;
            if (flag) {
                coverTMP.renderMode(this.mode, this.slot, partialTicks);
                // render machine
                RenderHelper.renderItemOverLay(-2.6f, -2.65f, 0.003f,1/100f, coverTMP.coverHolder.getStackForm());

                // render name
                RenderHelper.renderText(0, -3.5f/16, 0, 1.0f / 200, 0XFFFFFFFF, I18n.format(((MetaTileEntity) coverTMP.coverHolder).getMetaFullName()), true);
            }
            // render frame
            RenderHelper.renderRect(-7f/16, -7f/16, 14f/16, 0.01f,0.003f, frameColor);
            RenderHelper.renderRect(-7f/16, -4f/16 - 0.01f, 14f/16, 0.01f,0.003f, frameColor);
            RenderHelper.renderRect(-7f/16, -7f/16 + 0.01f, 0.01f, 3f/16 - 0.02f,0.003f, frameColor);
            RenderHelper.renderRect(7f/16 - 0.01f, -7f/16 + 0.01f, 0.01f, 3f/16 - 0.02f,0.003f, frameColor);

            RenderHelper.renderRect(-7f/16, -3f/16, 14f/16, 0.01f,0.003f, frameColor);
            RenderHelper.renderRect(-7f/16, 7f/16 - 0.01f, 14f/16, 0.01f,0.003f, frameColor);
            RenderHelper.renderRect(-7f/16, -3f/16 + 0.01f, 0.01f, 10f/16 - 0.02f,0.003f, frameColor);
            RenderHelper.renderRect(7f/16 - 0.01f, -3f/16 + 0.01f, 0.01f, 10f/16 - 0.02f,0.003f, frameColor);
        }
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        writeSync(buf);
        buf.writeItemStack(this.inventory.getStackInSlot(0));
        if (plugin != null) {
            plugin.writeInitialSyncData(buf);
        }
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        readSync(buf);
        try {
            ItemStack itemStack = buf.readItemStack();
            MonitorPluginBaseBehavior behavior = MonitorPluginBaseBehavior.getBehavior(itemStack);
            if (behavior == null) {
                unloadPlugin();
            } else {
                this.inventory.setStackInSlot(0, itemStack);
                loadPlugin(behavior);
                plugin.receiveInitialSyncData(buf);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if(dataId == 1) {
            readSync(buf);
        } else if(dataId == 2) { //plugin
            if (plugin != null) {
                plugin.readPluginData(buf.readVarInt(), buf);
            }
        } else if (dataId == 3) {
            try {
                ItemStack itemStack = buf.readItemStack();
                MonitorPluginBaseBehavior behavior = MonitorPluginBaseBehavior.getBehavior(itemStack);
                if (behavior == null) {
                    unloadPlugin();
                } else {
                    this.inventory.setStackInSlot(0, itemStack);
                    loadPlugin(behavior);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        if (this.coverPos != null) {
            data.setTag("coverPos", NBTUtil.createPosTag(this.coverPos.getKey()));
            data.setByte("coverSide", (byte) this.coverPos.getValue().getIndex());
        }
        data.setByte("mode", (byte) this.mode.ordinal());
        data.setFloat("scale", this.scale);
        data.setInteger("color", this.frameColor);
        data.setInteger("slot", this.slot);
        data.setTag("Inventory", this.inventory.serializeNBT());
        return super.writeToNBT(data);
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.frameColor = data.hasKey("color")? data.getInteger("color") : 0XFF00Ff00;
        this.scale = data.hasKey("scale")? data.getFloat("scale") : 1;
        this.slot = data.hasKey("slot")? data.getInteger("slot") : 0;
        this.mode = CoverDigitalInterface.MODE.VALUES[data.hasKey("mode")? data.getByte("mode") : 0];
        this.inventory.deserializeNBT(data.getCompoundTag("Inventory"));
        if (data.hasKey("coverPos") && data.hasKey("coverSide")) {
            BlockPos pos = NBTUtil.getPosFromTag(data.getCompoundTag("coverPos"));
            EnumFacing side = EnumFacing.byIndex(data.getByte("coverSide"));
            this.coverPos = new Tuple<>(pos, side);
        } else {
            this.coverPos = null;
        }
        MonitorPluginBaseBehavior behavior = MonitorPluginBaseBehavior.getBehavior(this.inventory.getStackInSlot(0));
        if (behavior == null) {
            unloadPlugin();
        } else {
            loadPlugin(behavior);
        }
    }

    @Override
    protected void initializeInventory() {
        super.initializeInventory();
        this.inventory = new ItemStackHandler(){
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
                MonitorPluginBaseBehavior behavior = MonitorPluginBaseBehavior.getBehavior(stack);
                return behavior != null;
            }

            @Nonnull
            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if(!getWorld().isRemote && !getStackInSlot(slot).isEmpty() && !simulate) {
                    unloadPlugin();
                    writeCustomData(3, packetBuffer -> {
                        packetBuffer.writeItemStack(ItemStack.EMPTY);
                    });
                }
                return super.extractItem(slot, amount, simulate);
            }
        };
        this.itemInventory = this.inventory;
    }

    @Override
    public boolean shouldRenderOverlay() {
        MultiblockControllerBase controller = this.getController();
        return controller instanceof MetaTileEntityCentralMonitor && ((MetaTileEntityCentralMonitor) controller).isActive();
    }

    @Override
    public boolean canPlaceCoverOnSide(EnumFacing side) {
        return this.getController() != null && this.getController().getFrontFacing() != side;
    }

    @Override
    public void removeFromMultiBlock(MultiblockControllerBase controllerBase) {
        super.removeFromMultiBlock(controllerBase);
        this.setMode(null, this.mode);
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            CoverBehavior coverBehavior = getCoverFromPosSide(this.coverPos);
            if (coverBehavior != null && coverBehavior.coverHolder!= null) {
                return coverBehavior.coverHolder.getCapability(capability, coverBehavior.attachedSide);
            }
        }
        return null;
    }

    @Override
    public MetaTileEntity createMetaTileEntity(MetaTileEntityHolder metaTileEntityHolder) {
        return new MetaTileEntityMonitorScreen(this.metaTileEntityId);
    }

    @Override
    protected ModularUI createUI(EntityPlayer entityPlayer) {
        MultiblockControllerBase controller = this.getController();
        if (controller instanceof MetaTileEntityCentralMonitor && ((MetaTileEntityCentralMonitor) controller).isActive()) {
            int width = 330;
            int height = 260;
            ToggleButtonWidget[] buttons = new ToggleButtonWidget[5];
            buttons[0] = new ToggleButtonWidget(width - 135, 25, 20, 20, ClientHandler.BUTTON_FLUID, ()->this.mode == CoverDigitalInterface.MODE.FLUID, (isPressed)->{
                if (isPressed) setMode(CoverDigitalInterface.MODE.FLUID);
            }).setTooltipText("metaitem.cover.digital.mode.fluid");
            buttons[1] = new ToggleButtonWidget(width - 115, 25, 20, 20, ClientHandler.BUTTON_ITEM, ()->this.mode == CoverDigitalInterface.MODE.ITEM, (isPressed)->{
                if (isPressed) setMode(CoverDigitalInterface.MODE.ITEM);
            }).setTooltipText("metaitem.cover.digital.mode.item");
            buttons[2] = new ToggleButtonWidget(width - 95, 25, 20, 20, ClientHandler.BUTTON_ENERGY, ()->this.mode == CoverDigitalInterface.MODE.ENERGY, (isPressed)->{
                if (isPressed) setMode(CoverDigitalInterface.MODE.ENERGY);
            }).setTooltipText("metaitem.cover.digital.mode.energy");
            buttons[3] = new ToggleButtonWidget(width - 75, 25, 20, 20, ClientHandler.BUTTON_MACHINE, ()->this.mode == CoverDigitalInterface.MODE.MACHINE, (isPressed)->{
                if (isPressed) setMode(CoverDigitalInterface.MODE.MACHINE);
            }).setTooltipText("metaitem.cover.digital.mode.machine");
            buttons[4] = new ToggleButtonWidget(width - 35, 25, 20, 20, ClientHandler.BUTTON_INTERFACE, ()->this.mode == CoverDigitalInterface.MODE.PROXY, (isPressed)->{
                if (isPressed) setMode(CoverDigitalInterface.MODE.PROXY);
            }).setTooltipText("metaitem.cover.digital.mode.proxy");
            List<CoverDigitalInterface> covers = new ArrayList<>();
            ((MetaTileEntityCentralMonitor) controller).covers.forEach(coverPos->{
                covers.add(getCoverFromPosSide(coverPos));
            });
            WidgetPluginConfig pluginWidget = new WidgetPluginConfig();
            return ModularUI.builder(GuiTextures.BOXED_BACKGROUND, width, height)
                    .widget(pluginWidget)
                    .widget(new WidgetMonitorScreen(330, 0, 150, this))
                    .widget(new LabelWidget(15, 13, "gtadditions.machine.monitor_screen.name", 0XFFFFFFFF))
                    .widget(new ClickButtonWidget(15, 25, 40, 20, "monitor.gui.title.back", data->{
                        if (((MetaTileEntityCentralMonitor)controller).isActive() && controller.isValid())
                            MetaTileEntityUIFactory.INSTANCE.openUI(controller.getHolder(), (EntityPlayerMP) entityPlayer);
                    }))

                    .widget(new LabelWidget(15, 55, "monitor.gui.title.scale", 0xFFFFFFFF))
                    .widget(new ClickButtonWidget(50, 50, 20, 20, "-1", (data) -> setConfig(this.slot, ((float)Math.round((scale - (data.isShiftClick? 1.0f:0.1f))*10)/10), this.frameColor)))
                    .widget(new ClickButtonWidget(130, 50, 20, 20, "+1", (data) -> setConfig(this.slot, ((float)Math.round((scale + (data.isShiftClick? 1.0f:0.1f))*10)/10), this.frameColor)))
                    .widget(new ImageWidget(70, 50, 60, 20, GuiTextures.DISPLAY))
                    .widget(new SimpleTextWidget(100, 60, "", 16777215, () -> Float.toString(scale)))

                    .widget(new LabelWidget(15, 85, "monitor.gui.title.argb", 0xFFFFFFFF))
                    .widget(new WidgetARGB(50, 80, 20, this.frameColor, (color)->setConfig(this.slot, this.scale, color)))

                    .widget(new LabelWidget(15, 110, "monitor.gui.title.slot", 0xFFFFFFFF))
                    .widget(new ClickButtonWidget(50, 105, 20, 20, "-1", (data) -> setConfig(this.slot - 1, this.scale, this.frameColor)))
                    .widget(new ClickButtonWidget(130, 105, 20, 20, "+1", (data) -> setConfig(this.slot + 1, this.scale, this.frameColor)))
                    .widget(new ImageWidget(70, 105, 60, 20, GuiTextures.DISPLAY))
                    .widget(new SimpleTextWidget(100, 115, "", 16777215, () -> Integer.toString(slot)))

                    .widget(new LabelWidget(15, 135, "monitor.gui.title.plugin", 0xFFFFFFFF))
                    .widget(new SlotWidget(inventory, 0, 50, 130, true, true)
                            .setBackgroundTexture(GuiTextures.SLOT)
                            .setChangeListener(()->{
                                if (this.getWorld() != null && !this.getWorld().isRemote) {
                                    MonitorPluginBaseBehavior behavior = MonitorPluginBaseBehavior.getBehavior(inventory.getStackInSlot(0));
                                    if(behavior == null) {
                                        unloadPlugin();
                                    } else {
                                        loadPlugin(behavior);
                                    }
                                    writeCustomData(3, packetBuffer -> {
                                        packetBuffer.writeItemStack(inventory.getStackInSlot(0));
                                    });
                                }
                            }))
                    .widget(new ClickButtonWidget(80, 130, 40, 20, "monitor.gui.title.config", (data)->{
                        if (plugin != null) {
                            if (pluginWidget.getContainedWidgets(true).size() > 0) {
                                pluginWidget.removePluginWidget();
                            } else {
                                plugin.customUI(pluginWidget, this.getHolder(), entityPlayer);
                            }
                        }
                    }) {
                        @Override
                        protected void triggerButton() {
                            super.triggerButton();
                            if (plugin != null) {
                                if (pluginWidget.getContainedWidgets(true).size() > 0) {
                                    pluginWidget.removePluginWidget();
                                } else {
                                    plugin.customUI(pluginWidget, getHolder(), entityPlayer);
                                }
                            }
                        }
                    })

                    .widget(new WidgetCoverList(width - 140, 50, 120, 11, covers, getCoverFromPosSide(this.coverPos), (coverPos) -> {
                        if (coverPos == null) {
                            this.setMode(null, this.mode);
                        } else {
                            this.setMode(new Tuple<>(coverPos.coverHolder.getPos(), coverPos.attachedSide));
                        }
                    }))

                    .widget(buttons[0])
                    .widget(buttons[1])
                    .widget(buttons[2])
                    .widget(buttons[3])
                    .widget(buttons[4])

                    .bindPlayerInventory(entityPlayer.inventory, GuiTextures.SLOT, 15, 170)
                    .build(this.getHolder(), entityPlayer);
        }
        return null;
    }

    // adaptive click, supports scaling. x and y is the pos of the origin screen (scale = 1). this func must be called when screen is active.
    public boolean onClickLogic(EntityPlayer playerIn, EnumHand hand, EnumFacing facing, boolean isRight, double x, double y) {
        if (this.plugin != null) {
            boolean flag = this.plugin.onClickLogic(playerIn, hand, facing, isRight, x, y);
            if (flag) return flag;
        }
        if (this.getWorld().isRemote) return true;
        CoverDigitalInterface coverBehavior = getCoverFromPosSide(this.coverPos);
        if (isRight) {
            if (coverBehavior != null && coverBehavior.isProxy() && coverBehavior.coverHolder!= null && this.mode != CoverDigitalInterface.MODE.PROXY) {
                if (playerIn.isSneaking() && playerIn.getHeldItemMainhand().isEmpty() && this.plugin == null) {
                    if (1f / 16 < x && x < 4f / 16 && 1f / 16 < y && y < 4f / 16) {
                        this.setConfig(this.slot - 1, this.scale, this.frameColor);
                        return true;
                    } else if (12f / 16 < x && x < 15f / 16 && 1f / 16 < y && y < 4f / 16) {
                        this.setConfig(this.slot + 1, this.scale, this.frameColor);
                        return true;
                    }
                }
                if(coverBehavior.modeRightClick(playerIn, hand, this.mode, this.slot) == EnumActionResult.PASS) {
                    if (!playerIn.isSneaking() && this.openGUIOnRightClick()) {
                        MetaTileEntityUIFactory.INSTANCE.openUI(((MetaTileEntity)coverBehavior.coverHolder).getHolder(), (EntityPlayerMP)playerIn);
                        return true;
                    } else {
                        return false;
                    }
                }
                return true;
            }
        } else {
            if (coverBehavior != null && coverBehavior.isProxy() && coverBehavior.coverHolder!= null && this.mode != CoverDigitalInterface.MODE.PROXY) {
                return coverBehavior.modeLeftClick(playerIn, this.mode, this.slot);
            }
        }
        return false;
    }

    private double[] handleRayTraceResult(RayTraceResult rayTraceResult, EnumFacing spin) {
        double x = 0;
        double y = 0;
        double dX = rayTraceResult.sideHit.getAxis() == EnumFacing.Axis.X
                ? rayTraceResult.hitVec.z - rayTraceResult.getBlockPos().getZ()
                : rayTraceResult.hitVec.x - rayTraceResult.getBlockPos().getX();
        double dY = rayTraceResult.sideHit.getAxis() == EnumFacing.Axis.Y
                ? rayTraceResult.hitVec.z - rayTraceResult.getBlockPos().getZ()
                : rayTraceResult.hitVec.y - rayTraceResult.getBlockPos().getY();
        if (spin == EnumFacing.NORTH) {
            x = 1 - dX;
            y = 1 - dY;
            if(rayTraceResult.sideHit.getYOffset() < 0) {
                y = 1 - y;
            }
        } else if (spin == EnumFacing.SOUTH) {
            x = dX;
            y = dY;
            if(rayTraceResult.sideHit.getYOffset() < 0) {
                y = 1 - y;
            }
        } else if (spin == EnumFacing.EAST) {
            x = 1 - dY;
            y = dX;
            if (rayTraceResult.sideHit.getXOffset() < 0 || rayTraceResult.sideHit.getZOffset() > 0) {
                x = 1 - x;
                y = 1 - y;
            } else if(rayTraceResult.sideHit.getYOffset() < 0) {
                y = 1 - y;
            }
        } else {
            x = dY;
            y = 1 - dX;
            if (rayTraceResult.sideHit.getXOffset() < 0 || rayTraceResult.sideHit.getZOffset() > 0) {
                x = 1 - x;
                y = 1 - y;
            }else if(rayTraceResult.sideHit.getYOffset() < 0) {
                y = 1 - y;
            }
        }
        if (rayTraceResult.sideHit == EnumFacing.WEST || rayTraceResult.sideHit == EnumFacing.SOUTH) {
            x = 1- x;
        } else if (rayTraceResult.sideHit == EnumFacing.UP) {
            x = 1 - x;
            y = 1 - y;
        }
        return new double[]{x, y};
    }

    private boolean handleHitResultWithScale(EntityPlayer playerIn, EnumHand hand, EnumFacing facing, boolean isRight, CuboidRayTraceResult rayTraceResult) {
        boolean flag = false;
        if (rayTraceResult != null && rayTraceResult.typeOfHit == RayTraceResult.Type.BLOCK && this.getController() != null) {
            double[] pos = handleRayTraceResult(rayTraceResult, BlockPatternChecker.getSpin(this.getController()));
            MetaTileEntityMonitorScreen[][] screens = ((MetaTileEntityCentralMonitor)this.getController()).screens;
            int mX = this.getX(), mY = this.getY();
            int max = Math.max(mX, mY);
            for (int i = 0; i <= max && mX - i >= 0; i++) {
                for (int j = 0; j <= max && mY - j >= 0; j++) {
                    MetaTileEntityMonitorScreen screen = screens[mX - i][mY - j];
                    if (screen != null && screen.isActive()) {
                        double xR = (pos[0] + i) / screen.scale;
                        double yR = (pos[1] + j) / screen.scale;
                        if (xR >= 0 && xR <= 1 && yR >= 0 && yR <= 1)
                            if (screen.onClickLogic(playerIn, hand, facing, isRight, xR, yR)) {
                                flag = true;
                            }
                    }
                }
            }
        }
        return flag;
    }

    @SideOnly(Side.CLIENT)
    public Tuple<Double, Double> checkLookingAt(float partialTicks) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (this.getWorld() != null && player != null) {
            RayTraceResult rayTraceResult = player.rayTrace(Minecraft.getMinecraft().playerController.getBlockReachDistance(), partialTicks);
            MultiblockControllerBase controller = this.getController();
            if (rayTraceResult != null && rayTraceResult.typeOfHit == RayTraceResult.Type.BLOCK && controller != null && rayTraceResult.sideHit == controller.getFrontFacing()) {
                int i = -1,j = -1;
                TileEntity tileEntity = this.getWorld().getTileEntity(rayTraceResult.getBlockPos());
                if (tileEntity instanceof MetaTileEntityHolder && ((MetaTileEntityHolder) tileEntity).getMetaTileEntity() instanceof MetaTileEntityMonitorScreen){
                    MetaTileEntityMonitorScreen screenHit = (MetaTileEntityMonitorScreen) ((MetaTileEntityHolder) tileEntity).getMetaTileEntity();
                    if (controller == screenHit.getController()) {
                        i = ((MetaTileEntityMonitorScreen) ((MetaTileEntityHolder) tileEntity).getMetaTileEntity()).getX() - this.getX();
                        j = ((MetaTileEntityMonitorScreen) ((MetaTileEntityHolder) tileEntity).getMetaTileEntity()).getY() - this.getY();
                        double[] pos =  handleRayTraceResult(rayTraceResult, BlockPatternChecker.getSpin(controller));
                        if ((i >= 0 && j >= 0)) {
                            pos[0] = (pos[0] + i) / this.scale;
                            pos[1] = (pos[1] + j) / this.scale;
                            if (pos[0] >= 0 && pos[0] <= 1 && pos[1] >= 0 && pos[1] <= 1)
                                return new Tuple<Double, Double>(pos[0], pos[1]);
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean onRightClick(EntityPlayer playerIn, EnumHand hand, EnumFacing facing, CuboidRayTraceResult hitResult) {
        if (!(!playerIn.isSneaking() && playerIn.getHeldItemMainhand().hasCapability(GregtechCapabilities.CAPABILITY_SCREWDRIVER, (EnumFacing)null))
        && !GATileEntities.MONITOR_SCREEN.getStackForm().isItemEqual(playerIn.getHeldItemMainhand())) {
            if (playerIn.world.getTotalWorldTime() - lastClickTime < 2 &&
                    playerIn.getPersistentID().equals(lastClickUUID)) {
                return true;
            }
            lastClickTime = playerIn.world.getTotalWorldTime();
            lastClickUUID = playerIn.getPersistentID();

            MultiblockControllerBase controller = this.getController();
            if (controller instanceof MetaTileEntityCentralMonitor &&
                    ((MetaTileEntityCentralMonitor) controller).isActive() && controller.getFrontFacing() == facing) {
                return handleHitResultWithScale(playerIn, hand, facing, true, hitResult);
            }
        }
        return false;
    }

    @Override
    public boolean onScrewdriverClick(EntityPlayer playerIn, EnumHand hand, EnumFacing facing, CuboidRayTraceResult hitResult) {
        if (!playerIn.isSneaking() && this.getWorld() != null && !this.getWorld().isRemote) {
            MetaTileEntityUIFactory.INSTANCE.openUI(this.getHolder(), (EntityPlayerMP)playerIn);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onLeftClick(EntityPlayer playerIn, EnumFacing facing, CuboidRayTraceResult hitResult) {
        if (playerIn.world.getTotalWorldTime() - lastClickTime < 2 && playerIn.getPersistentID().equals(lastClickUUID)) {
            return;
        }
        lastClickTime = playerIn.world.getTotalWorldTime();
        lastClickUUID = playerIn.getPersistentID();
        MultiblockControllerBase controller = this.getController();
        if (controller != null && controller.getFrontFacing() == facing) {
            handleHitResultWithScale(playerIn, null, facing, false, hitResult);
        }
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, boolean advanced) {
        super.addInformation(stack, player, tooltip, advanced);
        tooltip.add(I18n.format("gtadditions.multiblock.monitor_screen.tooltip.1"));
        tooltip.add(I18n.format("gtadditions.multiblock.monitor_screen.tooltip.2"));
        tooltip.add(I18n.format("gtadditions.multiblock.monitor_screen.tooltip.3"));
    }
}
