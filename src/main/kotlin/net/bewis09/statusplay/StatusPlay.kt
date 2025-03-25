package net.bewis09.statusplay

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.command.argument.ColorArgumentType
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.command.argument.TextArgumentType
import net.minecraft.registry.RegistryWrapper
import net.minecraft.scoreboard.ServerScoreboard
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

object StatusPlay : ModInitializer {
	private val gson = Gson()
	val statusList: ArrayList<Status> = ArrayList()

	private var file_loaded = false

	private val logger: Logger = LoggerFactory.getLogger("StatusPlay")

	override fun onInitialize() {
		CommandRegistrationCallback.EVENT.register { dispatcher, registryAccess, _ ->
			dispatcher.register(
				CommandManager.literal("status").then(
					CommandManager.argument("status", StringArgumentType.word()).suggests(
						StatusSuggestionProvider()
					).executes { context: CommandContext<ServerCommandSource> ->
						val source = context.source
						if (source.player == null) {
							source.sendFeedback({ Text.literal("You must be a player to use this command").formatted(Formatting.RED) }, false)
							return@executes 0
						}

						val teamId = context.getArgument("status", String::class.java)
						val team = statusList.firstOrNull { status: Status -> status.name == teamId }
						if (team == null) {
							source.sendFeedback({ Text.literal("Invalid status").formatted(Formatting.RED) }, false)
							return@executes 0
						}

						val server = source.server
						server.scoreboard.addScoreHolderToTeam(source.name, server.scoreboard.getTeam(team.name))
						source.sendFeedback({ Text.literal("Your status has been set to ").append(team.text) }, false)
						1
					}
				).executes {
					val source = it.source
					if (source.player == null) {
						source.sendFeedback({ Text.literal("You must be a player to use this command").formatted(Formatting.RED) }, false)
						return@executes 0
					}

					val server = source.server
					server.scoreboard.removeScoreHolderFromTeam(source.name, server.scoreboard.getScoreHolderTeam(source.name))
					source.sendFeedback({ Text.literal("Your status has been removed") }, false)
					1
				}
			)

			dispatcher.register(
				CommandManager.literal("ms").requires { it.player?.hasPermissionLevel(2) != false }.then(
					CommandManager.literal("add").then(
						CommandManager.argument(
							"id", StringArgumentType.word()
						).then(CommandManager.literal("colored").then(CommandManager.argument("name", StringArgumentType.word()).then(CommandManager.argument("color", ColorArgumentType.color()).executes {
							val source = it.source
							val id = it.getArgument("id", String::class.java)
							val name = it.getArgument("name", String::class.java)
							val text = Text.literal("[$name]").formatted(it.getArgument("color", Formatting::class.java))
							addStatus(id, text, it.source.server, registryAccess)
							source.sendFeedback({ Text.literal("Added status ").append(text) }, false)
							1
						}))).then(CommandManager.literal("custom").then(CommandManager.argument("text", TextArgumentType.text(registryAccess)).executes {
							val source = it.source
							val id = it.getArgument("id", String::class.java)
							val text = it.getArgument("text", Text::class.java)
							addStatus(id, text.copy(), it.source.server, registryAccess)
							source.sendFeedback({ Text.literal("Added status ").append(text) }, false)
							1
						}))
					)
				).then(
					CommandManager.literal("remove").then(
						CommandManager.argument(
							"id", StringArgumentType.word()
						).suggests(StatusSuggestionProvider()).executes {
							val source = it.source
							val id = it.getArgument("id", String::class.java)
							if (statusList.none { status: Status -> status.name == id }) {
								source.sendFeedback({ Text.literal("Invalid status").formatted(Formatting.RED) }, false)
								return@executes 0
							}
							removeStatus(id, it.source.server, registryAccess)
							source.sendFeedback({ Text.of("Removed status $id") }, false)
							1
						})
				).then(
					CommandManager.literal("set").then(
						CommandManager.argument(
							"player", EntityArgumentType.player()
						).then(CommandManager.argument("status", StringArgumentType.word()).suggests(StatusSuggestionProvider()).executes {
							val source = it.source
							val player = EntityArgumentType.getPlayer(it, "player")
							val id = it.getArgument("status", String::class.java)
							val team = statusList.firstOrNull { status: Status -> status.name == id }
							if (team == null) {
								source.sendFeedback({ Text.literal("Invalid status").formatted(Formatting.RED) }, false)
								return@executes 0
							}
							it.source.server.scoreboard.addScoreHolderToTeam(player.gameProfile.name, it.source.server.scoreboard.getTeam(team.name))
							source.sendFeedback({ Text.literal("Set status of ").append(player.name).append(" to ").append(team.text) }, false)
							1
						}).executes {
							val source = it.source
							val player = EntityArgumentType.getPlayer(it, "player")
							val server = source.server
							server.scoreboard.removeScoreHolderFromTeam(player.gameProfile.name, server.scoreboard.getScoreHolderTeam(player.gameProfile.name))
							source.sendFeedback({ Text.literal("Removed status of ").append(player.name) }, false)
							1
						}
					)
				)
			)
		}
	}

	private fun addStatus(text: String, name: MutableText, server: MinecraftServer, registryWrapper: RegistryWrapper.WrapperLookup) {
		statusList.removeIf { it.name == text }
		statusList.add(Status(text.lowercase().replace(" ","_"), name))
		writeStatus(registryWrapper)
		reloadStatusTeams(server.scoreboard, registryWrapper)
	}

	private fun removeStatus(name: String, server: MinecraftServer, registryWrapper: RegistryWrapper.WrapperLookup) {
		statusList.removeIf { it.name == name }
		writeStatus(registryWrapper)
		server.scoreboard.removeTeam(server.scoreboard.getTeam(name))
		reloadStatusTeams(server.scoreboard, registryWrapper)
	}

	private fun writeStatus(registryWrapper: RegistryWrapper.WrapperLookup) {
		val file = File(FabricLoader.getInstance().configDir.toString(), "statusplay.json")
		file.writeText(gson.toJson(statusList.map { status: Status ->
			val obj = JsonObject()
			obj.addProperty("name", status.name)
			obj.addProperty("text", Text.Serialization.toJsonString(status.text, registryWrapper))
			obj
		}))
	}

	data class Status(val name: String, val text: MutableText)

	class StatusSuggestionProvider : SuggestionProvider<ServerCommandSource?> {
		override fun getSuggestions(context: CommandContext<ServerCommandSource?>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
			statusList.forEach {
				builder.suggest(it.name)
			}
			return builder.buildFuture()
		}
	}

	fun reloadStatusTeams(scoreboard: ServerScoreboard, registryWrapper: RegistryWrapper.WrapperLookup) {
		if(!file_loaded) {
			file_loaded = true

			val file = File(FabricLoader.getInstance().configDir.toString(), "statusplay.json")

			if (!file.exists()) {
				file.createNewFile()
				file.writeText("[]")
			}

			try {
				gson.fromJson(file.readText(), JsonArray().javaClass).forEach {
					val obj = it.asJsonObject
					val text = Text.Serialization.fromJson(obj["text"].asString, registryWrapper)

					if (text != null)
						statusList.add(Status(obj["name"].asString, text))
				}
			} catch (_: Exception) {
				logger.error("Failed to load statusplay.json")
			}
		}

		statusList.forEach(Consumer { status: Status ->
			var team = scoreboard.getTeam(status.name)
			if (team == null) {
				team = scoreboard.addTeam(status.name)
				team.displayName = status.text
			}

			team?.prefix = status.text.copy().append(" ")
		})
	}
}