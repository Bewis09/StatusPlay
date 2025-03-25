package net.bewis09.statusplay.mixin;

import net.bewis09.statusplay.StatusPlay;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class TeamInjectorMixin {
	@Shadow public abstract ServerScoreboard getScoreboard();

	@Shadow public abstract DynamicRegistryManager.Immutable getRegistryManager();

	@Inject(at = @At("HEAD"), method = "loadWorld")
	private void init(CallbackInfo info) {
		StatusPlay.INSTANCE.reloadStatusTeams(this.getScoreboard(), this.getRegistryManager());
	}
}