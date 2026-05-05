package me.neznamy.tab.shared.features.nametags.unlimited;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.api.nametag.UnlimitedNameTagManager;
import me.neznamy.tab.shared.features.types.DisableChecker;
import me.neznamy.tab.shared.features.types.JoinListener;
import me.neznamy.tab.shared.features.types.Loadable;
import me.neznamy.tab.shared.features.types.QuitListener;
import me.neznamy.tab.shared.features.types.Refreshable;
import me.neznamy.tab.shared.features.types.ServerSwitchListener;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.shared.features.types.UnLoadable;
import me.neznamy.tab.shared.features.types.WorldSwitchListener;
import me.neznamy.tab.shared.placeholders.conditions.Condition;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.TAB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;


@Getter
public abstract class NameTagX extends TabFeature implements UnlimitedNameTagManager, JoinListener, QuitListener,
        Loadable, UnLoadable, WorldSwitchListener, ServerSwitchListener, Refreshable {

    //config options
    private final boolean disableOnBoats = config().getBoolean("scoreboard-teams.unlimited-nametag-mode.disable-on-boats", true);
    private final List<String> dynamicLines = new ArrayList<>(config().getStringList("scoreboard-teams.unlimited-nametag-mode.dynamic-lines", Arrays.asList(TabConstants.Property.ABOVENAME, TabConstants.Property.NAMETAG, TabConstants.Property.BELOWNAME, "another")));
    private final Map<String, Object> staticLines = config().getConfigurationSection("scoreboard-teams.unlimited-nametag-mode.static-lines");
    private final boolean armorStandsAlwaysVisible = TAB.getInstance().getConfiguration().getSecretOption("scoreboard-teams.unlimited-nametag-mode.always-visible", false);

    private final BiFunction<NameTagX, TabPlayer, ArmorStandManager> armorStandFunction;
    private final DisableChecker unlimitedDisableChecker;

    protected NameTagX(@NonNull BiFunction<NameTagX, TabPlayer, ArmorStandManager> armorStandFunction) {
        this.armorStandFunction = armorStandFunction;
        Collections.reverse(dynamicLines);
        Condition disableCondition = Condition.getCondition(config().getString("scoreboard-teams.unlimited-nametag-mode.disable-condition"));
        unlimitedDisableChecker = new DisableChecker(getExtraFeatureName(), disableCondition, this::onUnlimitedDisableConditionChange, p -> p.disabledUnlimitedNametags);
        TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.UNLIMITED_NAME_TAGS + "-Condition", unlimitedDisableChecker);
    }

    public boolean isPlayerDisabled(@NonNull TabPlayer p) {
        return p.disabledUnlimitedNametags.get() ||
                p.unlimitedNametagData.handlingPaused ||
                p.unlimitedNametagData.disabledWithAPI ||
                p.unlimitedNametagData.hiddenNameTag;
    }

    @Override
    public void load() {
        for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
            updateProperties(all);
            all.unlimitedNametagData.armorStandManager = armorStandFunction.apply(this, all);
            if (unlimitedDisableChecker.isDisableConditionMet(all)) {
                addDisabledPlayer(all);
            }
            TAB.getInstance().getPlaceholderManager().getTabExpansion().setNameTagPreview(all, false);
        }
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        updateProperties(connectedPlayer);
        connectedPlayer.unlimitedNametagData.armorStandManager = armorStandFunction.apply(this, connectedPlayer);
        if (unlimitedDisableChecker.isDisableConditionMet(connectedPlayer))
            addDisabledPlayer(connectedPlayer);
        TAB.getInstance().getPlaceholderManager().getTabExpansion().setNameTagPreview(connectedPlayer, false);
    }

    public void addDisabledPlayer(@NotNull TabPlayer player) {
        player.disabledUnlimitedNametags.set(true);
    }

    @Override
    public void refresh(@NotNull TabPlayer refreshed, boolean force) {
        boolean changed = updateProperties(refreshed);
        if (isPlayerDisabled(refreshed)) return;
        refreshArmorStands(refreshed, force || changed);
    }

    @Override
    @NotNull
    public String getRefreshDisplayName() {
        return "Updating unlimited nametags";
    }

    @Override
    public void unload() {
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
            ArmorStandManager asm = p.unlimitedNametagData.armorStandManager;
            if (asm != null) {
                asm.destroy();
            } else {
                TAB.getInstance().getErrorManager().armorStandNull(p, "unload");
            }
        }
    }

    public void toggleNameTagPreview(TabPlayer player, boolean sendToggleMessage) {
        if (player.unlimitedNametagData.previewing) {
            setNameTagPreview(player, false);
            if (sendToggleMessage) player.sendMessage(TAB.getInstance().getConfiguration().getMessages().getNametagPreviewOff(), true);
            player.unlimitedNametagData.previewing = false;
        } else {
            setNameTagPreview(player, true);
            if (sendToggleMessage) player.sendMessage(TAB.getInstance().getConfiguration().getMessages().getNametagPreviewOn(), true);
            player.unlimitedNametagData.previewing = true;
        }
        TAB.getInstance().getPlaceholderManager().getTabExpansion().setNameTagPreview(player, player.unlimitedNametagData.previewing);
    }

    public void onUnlimitedDisableConditionChange(TabPlayer p, boolean disabledNow) {
        if (disabledNow) {
            pauseArmorStands(p);
        } else {
            resumeArmorStands(p);
        }
        refreshArmorStands(p, true);
    }

    /**
     * Updates raw values of properties for specified player
     *
     * @param   p
     *          player to update
     */
    public boolean updateProperties(@NonNull TabPlayer p) {
        boolean changed = p.loadPropertyFromConfig(this, TabConstants.Property.TAGPREFIX);
        if (p.loadPropertyFromConfig(this, TabConstants.Property.TAGSUFFIX)) changed = true;
        if (p.loadPropertyFromConfig(this, TabConstants.Property.CUSTOMTAGNAME, p.getName())) changed = true;
        if (p.setProperty(this, TabConstants.Property.NAMETAG, p.getProperty(TabConstants.Property.TAGPREFIX).getCurrentRawValue() +
                p.getProperty(TabConstants.Property.CUSTOMTAGNAME).getCurrentRawValue() + p.getProperty(TabConstants.Property.TAGSUFFIX).getCurrentRawValue())) changed = true;
        for (String property : dynamicLines) {
            if (!property.equals(TabConstants.Property.NAMETAG) && p.loadPropertyFromConfig(this, property)) changed = true;
        }
        for (String property : staticLines.keySet()) {
            if (!property.equals(TabConstants.Property.NAMETAG) && p.loadPropertyFromConfig(this, property)) changed = true;
        }
        return changed;
    }

    @Override
    public void onQuit(@NotNull TabPlayer disconnectedPlayer) {
        ArmorStandManager asm = disconnectedPlayer.unlimitedNametagData.armorStandManager;
        if (asm != null) asm.destroy();
    }

    @Override
    public void onServerChange(@NotNull TabPlayer p, @NotNull String from, @NotNull String to) {
        if (updateProperties(p) && !isPlayerDisabled(p)) refreshArmorStands(p, true);
    }

    @Override
    public void onWorldChange(@NotNull TabPlayer changed, @NotNull String from, @NotNull String to) {
        if (updateProperties(changed) && !isPlayerDisabled(changed)) refreshArmorStands(changed, true);
    }

    public abstract boolean isOnBoat(@NonNull TabPlayer player);

    public abstract void setNameTagPreview(@NonNull TabPlayer player, boolean status);

    public abstract void resumeArmorStands(@NonNull TabPlayer player);

    public abstract void pauseArmorStands(@NonNull TabPlayer player);

    public abstract void updateNameTagVisibilityView(@NonNull TabPlayer player);

    /* NameTag override */

    @Override
    public void hideNameTag(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (p.unlimitedNametagData.hiddenNameTag) return;
        p.unlimitedNametagData.hiddenNameTag = true;
        pauseArmorStands(p);
    }

    @Override
    public void hideNameTag(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull me.neznamy.tab.api.TabPlayer viewer) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.unlimitedNametagData.hiddenNameTagFor.add((TabPlayer) viewer)) return;
        refreshArmorStands(p, true);
    }

    @Override
    public void showNameTag(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.unlimitedNametagData.hiddenNameTag) return;
        p.unlimitedNametagData.hiddenNameTag = false;
        resumeArmorStands(p);
    }

    @Override
    public void showNameTag(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull me.neznamy.tab.api.TabPlayer viewer) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.unlimitedNametagData.hiddenNameTagFor.remove((TabPlayer) viewer)) return;
        refreshArmorStands(p, true);
    }

    @Override
    public boolean hasHiddenNameTag(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        return ((TabPlayer) player).unlimitedNametagData.hiddenNameTag;
    }

    @Override
    public boolean hasHiddenNameTag(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull me.neznamy.tab.api.TabPlayer viewer) {
        ensureActive();
        return ((TabPlayer) player).unlimitedNametagData.hiddenNameTagFor.contains((TabPlayer) viewer);
    }

    private void rebuildNameTagLine(@NonNull TabPlayer player) {
        player.setProperty(this, TabConstants.Property.NAMETAG, player.getProperty(TabConstants.Property.TAGPREFIX).getCurrentRawValue() +
                player.getProperty(TabConstants.Property.CUSTOMTAGNAME).getCurrentRawValue() + player.getProperty(TabConstants.Property.TAGSUFFIX).getCurrentRawValue());
    }

    @NotNull
    public String getExtraFeatureName() {
        return "Unlimited NameTags";
    }

    @Override
    @NotNull
    public String getFeatureName() {
        return getExtraFeatureName();
    }

    /* NameTagManager compatibility */

    @Override
    public void setPrefix(@NonNull me.neznamy.tab.api.TabPlayer player, @Nullable String prefix) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        p.getProperty(TabConstants.Property.TAGPREFIX).setTemporaryValue(prefix);
        rebuildNameTagLine(p);
        refreshArmorStands(p, true);
    }

    @Override
    public void setSuffix(@NonNull me.neznamy.tab.api.TabPlayer player, @Nullable String suffix) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        p.getProperty(TabConstants.Property.TAGSUFFIX).setTemporaryValue(suffix);
        rebuildNameTagLine(p);
        refreshArmorStands(p, true);
    }

    @Override
    public String getCustomPrefix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(TabConstants.Property.TAGPREFIX).getTemporaryValue();
    }

    @Override
    public String getCustomSuffix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(TabConstants.Property.TAGSUFFIX).getTemporaryValue();
    }

    @Override
    @NonNull
    public String getOriginalPrefix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(TabConstants.Property.TAGPREFIX).getOriginalRawValue();
    }

    @Override
    @NonNull
    public String getOriginalSuffix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(TabConstants.Property.TAGSUFFIX).getOriginalRawValue();
    }

    @Override
    public void pauseTeamHandling(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (p.unlimitedNametagData.handlingPaused) return;
        p.unlimitedNametagData.handlingPaused = true;
        pauseArmorStands(p);
    }

    @Override
    public void resumeTeamHandling(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.unlimitedNametagData.handlingPaused) return;
        p.unlimitedNametagData.handlingPaused = false;
        resumeArmorStands(p);
    }

    @Override
    public boolean hasTeamHandlingPaused(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        return ((TabPlayer) player).unlimitedNametagData.handlingPaused;
    }

    @Override
    public void setCollisionRule(@NonNull me.neznamy.tab.api.TabPlayer player, Boolean collision) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        p.unlimitedNametagData.forcedCollision = collision;
    }

    @Override
    public Boolean getCollisionRule(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.unlimitedNametagData.forcedCollision;
    }

    @Override
    public void toggleNameTagVisibilityView(@NonNull me.neznamy.tab.api.TabPlayer player, boolean sendToggleMessage) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        if (p.unlimitedNametagData.invisibleNameTagView) {
            p.unlimitedNametagData.invisibleNameTagView = false;
            if (sendToggleMessage) p.sendMessage(TAB.getInstance().getConfiguration().getMessages().getNameTagsShown(), true);
        } else {
            p.unlimitedNametagData.invisibleNameTagView = true;
            if (sendToggleMessage) p.sendMessage(TAB.getInstance().getConfiguration().getMessages().getNameTagsHidden(), true);
        }
        TAB.getInstance().getPlaceholderManager().getTabExpansion().setNameTagVisibility(p, !p.unlimitedNametagData.invisibleNameTagView);
        updateNameTagVisibilityView(p);
    }

    @Override
    public boolean hasHiddenNameTagVisibilityView(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        return ((TabPlayer) player).unlimitedNametagData.invisibleNameTagView;
    }

    // --------------------------------------
    // UnlimitedNameTagManager Implementation
    // --------------------------------------

    @Override
    public void disableArmorStands(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (p.unlimitedNametagData.disabledWithAPI) return;
        p.unlimitedNametagData.disabledWithAPI = true;
        pauseArmorStands(p);
    }

    @Override
    public void enableArmorStands(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.unlimitedNametagData.disabledWithAPI) return;
        p.unlimitedNametagData.disabledWithAPI = false;
        resumeArmorStands(p);
    }

    @Override
    public boolean hasDisabledArmorStands(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        return ((TabPlayer)player).unlimitedNametagData.disabledWithAPI;
    }

    @Override
    public void setName(@NonNull me.neznamy.tab.api.TabPlayer player, @Nullable String customName) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        p.getProperty(TabConstants.Property.CUSTOMTAGNAME).setTemporaryValue(customName);
        rebuildNameTagLine(p);
        refreshArmorStands(p, true);
    }

    @Override
    public void setLine(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull String line, @Nullable String value) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!getDefinedLines().contains(line)) throw new IllegalArgumentException("\"" + line + "\" is not a defined line. Defined lines: " + getDefinedLines());
        p.getProperty(line).setTemporaryValue(value);
        refreshArmorStands(p, true);
    }

    @Override
    public String getCustomName(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(TabConstants.Property.CUSTOMTAGNAME).getTemporaryValue();
    }

    @Override
    public String getCustomLineValue(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull String line) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(line).getTemporaryValue();
    }

    @Override
    @NotNull
    public String getOriginalName(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(TabConstants.Property.CUSTOMTAGNAME).getOriginalRawValue();
    }

    @Override
    @NotNull
    public String getOriginalLineValue(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull String line) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.getProperty(line).getOriginalRawValue();
    }

    @Override
    @NotNull
    public List<String> getDefinedLines() {
        ensureActive();
        List<String> lines = new ArrayList<>(dynamicLines);
        lines.addAll(staticLines.keySet());
        return lines;
    }

    private void refreshArmorStands(@NonNull TabPlayer p, boolean force) {
        ArmorStandManager asm = p.unlimitedNametagData.armorStandManager;
        if (asm != null) asm.refresh(force);
    }

    /**
     * Class storing unlimited nametag data for players.
     */
    public static class PlayerData {

        /** Armor stand manager */
        public ArmorStandManager armorStandManager;

        /** Whether player is previewing armor stands or not */
        public boolean previewing;

        /** Whether armor stands are disabled via API or not */
        public boolean disabledWithAPI;

        /** Whether player is riding a boat or not */
        public boolean onBoat;

        /** Whether armor stands are hidden globally through the name tag API */
        public boolean hiddenNameTag;

        /** Viewers who should not see this player's armor stands */
        public final Set<TabPlayer> hiddenNameTagFor = Collections.newSetFromMap(new WeakHashMap<>());

        /** Whether name tag handling is paused through the legacy API method */
        public boolean handlingPaused;

        /** Whether this player disabled armor stand name tags on all players */
        public boolean invisibleNameTagView;

        /** Stored API value kept for compatibility; unlimited mode does not apply collision rules */
        @Nullable
        public Boolean forcedCollision;
    }
}
