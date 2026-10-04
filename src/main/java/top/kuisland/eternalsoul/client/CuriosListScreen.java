package top.kuisland.eternalsoul.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.kuisland.eternalsoul.CurioIndex;
import top.kuisland.eternalsoul.network.ApplyConfigPacket;
import top.kuisland.eternalsoul.network.BulkTogglePacket;
import top.kuisland.eternalsoul.network.Network;

/**
 * 饰品列表面：顶部搜索栏，滚动列表（左侧物品贴图 + 名称 + 槽位类型，
 * 右侧模拟状态），点击条目切换开关。
 */
public class CuriosListScreen extends Screen {

    private static final int ROW_HEIGHT = 26;
    private static final int LIST_TOP = 36;
    private static final int BOTTOM_BAR = 30;

    private EditBox searchBox;
    private CurioListWidget list;
    private String lastFilter = "";
    private ItemStack hoveredIconStack = ItemStack.EMPTY;
    /** 物品显示名（小写）缓存：过滤与排序复用，避免每次击键重复构造与翻译 */
    private final Map<ResourceLocation, String> nameCache = new HashMap<>();

    public CuriosListScreen() {
        super(Component.translatable("eternalsoul.gui.title"));
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(this.width - 40, 340);
        this.list = new CurioListWidget(this.minecraft, this.width, this.height,
                LIST_TOP, this.height - BOTTOM_BAR, ROW_HEIGHT);
        this.searchBox = new EditBox(this.font, this.width / 2 - boxWidth / 2, 12,
                boxWidth, 18, Component.translatable("eternalsoul.gui.search_hint"));
        this.searchBox.setHint(Component.translatable("eternalsoul.gui.search_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setResponder(s -> {
            this.lastFilter = s;
            if (this.list != null) {
                this.list.refresh(s.trim().toLowerCase());
            }
        });
        addRenderableWidget(this.list);
        addRenderableWidget(this.searchBox);
        this.searchBox.setValue(this.lastFilter);
        addRenderableWidget(Button.builder(
                        Component.translatable("eternalsoul.gui.select_all"),
                        b -> bulk(BulkTogglePacket.SELECT_ALL))
                .bounds(this.width / 2 - 90, this.height - 26, 50, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("eternalsoul.gui.invert"),
                        b -> bulk(BulkTogglePacket.INVERT))
                .bounds(this.width / 2 - 36, this.height - 26, 50, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("eternalsoul.gui.apply_config"),
                        b -> applyConfig())
                .bounds(this.width / 2 + 18, this.height - 26, 72, 20).build());
        setInitialFocus(this.searchBox);
    }

    /** 应用配置文件的默认开关（用于全选/反选误操作后恢复初始设置） */
    private void applyConfig() {
        Network.CHANNEL.sendToServer(new ApplyConfigPacket());
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        // 状态由服务端同步回来后自动刷新（行渲染实时读取 ClientCache）
    }

    /** 每个批量包携带的ID数上限：巨表全选时分包发送，避免超出自定义负载32KB上限 */
    private static final int BULK_CHUNK = 200;

    /** 批量开关：仅作用于当前搜索过滤出的条目（无过滤时为全部），本地立即生效并通知服务端 */
    private void bulk(int mode) {
        String filter = this.lastFilter.trim().toLowerCase();
        List<String> scope = new ArrayList<>();
        for (CurioIndex.Entry entry : ClientCache.index()) {
            if (!matchesFilter(entry, filter)) {
                continue;
            }
            boolean enable = mode == BulkTogglePacket.SELECT_ALL
                    || !ClientCache.isEnabled(entry.itemId());
            ClientCache.setLocalEnabled(entry.itemId(), enable);
            scope.add(entry.itemId().toString());
        }
        // 分包发送：服务端按序处理，INVERT 语义按到达顺序叠加仍正确
        for (int i = 0; i < scope.size(); i += BULK_CHUNK) {
            Network.CHANNEL.sendToServer(new BulkTogglePacket(mode,
                    scope.subList(i, Math.min(scope.size(), i + BULK_CHUNK))));
        }
        if (scope.isEmpty()) {
            Network.CHANNEL.sendToServer(new BulkTogglePacket(mode, scope));
        }
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        refreshEntries();
    }

    /** 名称或注册 ID 包含过滤词（过滤词为空则全部匹配） */
    private boolean matchesFilter(CurioIndex.Entry entry, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        return lowerName(entry).contains(filter)
                || entry.itemId().toString().contains(filter);
    }

    private String lowerName(CurioIndex.Entry entry) {
        return nameCache.computeIfAbsent(entry.itemId(), id -> {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            return item == null ? ""
                    : new ItemStack(item).getHoverName().getString().toLowerCase();
        });
    }

    private void refreshEntries() {
        if (this.list != null && this.searchBox != null) {
            this.list.refresh(this.searchBox.getValue().trim().toLowerCase());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        this.hoveredIconStack = ItemStack.EMPTY;
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (!this.hoveredIconStack.isEmpty()) {
            guiGraphics.renderTooltip(this.font, this.hoveredIconStack, mouseX, mouseY);
        }

        List<CurioIndex.Entry> all = ClientCache.index();
        if (all.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("eternalsoul.gui.empty"),
                    this.width / 2, this.height / 2, 0xFF5555);
        } else {
            guiGraphics.drawString(this.font,
                    Component.translatable("eternalsoul.gui.count", all.size()),
                    8, this.height - BOTTOM_BAR + 6, 0xFFCCCCCC);
        }
        Component hint = Component.translatable("eternalsoul.gui.hint");
        guiGraphics.drawString(this.font, hint,
                this.width - this.font.width(hint) - 8,
                this.height - BOTTOM_BAR + 6, 0xFF888888);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && shouldCloseOnEsc()) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private class CurioListWidget extends ObjectSelectionList<CurioListWidget.Row> {

        CurioListWidget(Minecraft minecraft, int width, int height, int y0, int y1,
                        int itemHeight) {
            super(minecraft, width, height, y0, y1, itemHeight);
        }

        void refresh(String filter) {
            clearEntries();
            List<Row> rows = new ArrayList<>();
            for (CurioIndex.Entry entry : ClientCache.index()) {
                Item item = ForgeRegistries.ITEMS.getValue(entry.itemId());
                if (item == null || !CuriosListScreen.this.matchesFilter(entry, filter)) {
                    continue;
                }
                rows.add(new Row(new ItemStack(item), entry));
            }
            rows.sort(Comparator.comparing(
                    row -> CuriosListScreen.this.lowerName(row.entry),
                    String.CASE_INSENSITIVE_ORDER));
            rows.forEach(this::addEntry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(this.width - 30, 360);
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + this.getRowWidth() / 2 + 4;
        }

        class Row extends ObjectSelectionList.Entry<Row> {

            private final ItemStack stack;
            private final CurioIndex.Entry entry;

            Row(ItemStack stack, CurioIndex.Entry entry) {
                this.stack = stack;
                this.entry = entry;
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width,
                               int height, int mouseX, int mouseY, boolean hovered,
                               float partialTick) {
                if (hovered) {
                    guiGraphics.fill(left, top, left + width, top + height, 0x25FFFFFF);
                }
                guiGraphics.renderItem(this.stack, left + 4, top + 5);

                // 鼠标悬停在图标上时，显示原物品的完整信息
                if (mouseX >= left + 4 && mouseX < left + 20
                        && mouseY >= top + 5 && mouseY < top + 21) {
                    CuriosListScreen.this.hoveredIconStack = this.stack;
                }

                boolean on = ClientCache.isEnabled(this.entry.itemId());
                guiGraphics.drawString(CuriosListScreen.this.font,
                        this.stack.getHoverName(), left + 28, top + 4, 0xFFFFFFFF);

                Component state = on
                        ? Component.translatable("eternalsoul.gui.enabled")
                        : Component.translatable("eternalsoul.gui.disabled");
                int color = on ? 0xFF55FF55 : 0xFFFF5555;
                guiGraphics.drawString(CuriosListScreen.this.font, state,
                        left + width - CuriosListScreen.this.font.width(state) - 6, top + 4, color);

                guiGraphics.drawString(CuriosListScreen.this.font, this.entry.slot(),
                        left + 28, top + 15, 0xFF999999);
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.stack.getHoverName().getString());
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                toggle();
                return true;
            }

            private void toggle() {
                boolean newState = !ClientCache.isEnabled(this.entry.itemId());
                ClientCache.setLocalEnabled(this.entry.itemId(), newState);
                Network.CHANNEL.sendToServer(
                        new Network.TogglePacket(this.entry.itemId().toString(), newState));
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
        }
    }
}
