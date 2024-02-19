package net.fabricmc.minecraft.test.util.logging;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.util.Formatting;
import org.apache.commons.lang3.exception.ExceptionUtils;

import net.fabricmc.loader.impl.util.log.LogLevel;

import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.service.MixinService;

public class InGameLogger {

	private final static InGameLogger instance = new InGameLogger();
	private MinecraftServer minecraftServer;
	private final Queue<LogEvent> queue = new ConcurrentLinkedQueue<>();
	private final static ILogger log = MixinService.getService().getLogger("InGame");
    InGameLogger() {
        Thread thread = new Thread(() -> {
            while (true) {
                while (!queue.isEmpty()) {
                    var e = queue.poll();
                    Text label;
                    switch (e.level) {
                        case INFO ->
                                label = Text.of("[INFO]").copyContentOnly().setStyle(Style.EMPTY.withColor(Formatting.GREEN));
                        case WARN ->
                                label = Text.of("[WARN]").copyContentOnly().setStyle(Style.EMPTY.withColor(Formatting.YELLOW));
                        case ERROR ->
                                label = Text.of("[ERROR]").copyContentOnly().setStyle(Style.EMPTY.withColor(Formatting.RED));
                        default ->
                                label = Text.of("[%s]".formatted(e.level)).copyContentOnly().setStyle(Style.EMPTY.withColor(Formatting.GRAY));
                    }
                    Text message = Text.of(e.msg);
                    if (e.exc != null) {
                        message = message.copyContentOnly().setStyle(Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.of(ExceptionUtils.getMessage(e.exc)))));
                    }
                    Text t = Texts.join(List.of(label, message), Text.of(" "));
					minecraftServer.submit(() -> {
						minecraftServer.getPlayerManager().broadcast(t, false);
					});
                }
            }
        });
        thread.setName("Fabric - LoggingLoop");
		thread.start();
	}

	public void setMinecraftServer(MinecraftServer minecraftServer) {
		this.minecraftServer = minecraftServer;
	}

	public static void info(String msg, Object... args) {
		log.info(msg.formatted(args));
		instance.log(LogLevel.INFO, msg.formatted(args), null);
	}

	public static void error(String msg, Throwable exception, Object... args) {
		log.error(msg.formatted(args), exception);
		instance.log(LogLevel.ERROR, msg.formatted(args), exception);
	}

	public static void warn(String msg, Object... args) {
		log.warn(msg.formatted(args));
		instance.log(LogLevel.WARN, msg.formatted(args), null);
	}

	public void log(LogLevel level, String msg, Throwable exc) {
		queue.add(new LogEvent(level, msg, exc));

	}

	public static InGameLogger getInstance() {
		return instance;
	}

	record LogEvent(LogLevel level, String msg, Throwable exc) {

	}

}
