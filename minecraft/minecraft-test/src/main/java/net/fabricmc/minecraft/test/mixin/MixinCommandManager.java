package net.fabricmc.minecraft.test.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.minecraft.test.util.logging.InGameLogger;

@Mixin(CommandManager.class)
public abstract class MixinCommandManager {

	@Shadow
	@Final
	private CommandDispatcher<ServerCommandSource> dispatcher;

	@Shadow
	public static LiteralArgumentBuilder<ServerCommandSource> literal(String literal) {
		return null;
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	void registerCommand(CommandManager.RegistrationEnvironment environment, CommandRegistryAccess commandRegistryAccess, CallbackInfo ci) {
		dispatcher.register(literal("fabric").
				then(literal("reload").
						executes(ctx -> {
							var th = new Thread(() -> {
								var server = ctx.getSource().getServer();
								InGameLogger.getInstance().setMinecraftServer(server);
								InGameLogger.info("Reloading!");
								InGameLogger.warn("Mod Reloading is a highly experimental function, in some cases it can cause severe problems!");
								try {
									var start = System.nanoTime();
									FabricLoaderImpl.INSTANCE.unfreeze();
									FabricLoaderImpl.INSTANCE.reload();
									InGameLogger.info("Done reloading in %.3f milliseconds.", (System.nanoTime() - start)/1000f/1000f);
								}catch (Exception e){
									InGameLogger.error("Reload failed!", e);
								}
							});
							th.setName("Fabric - ReloadThread");
							th.start();
							return 0;
						})
				)
		);
	}
}
