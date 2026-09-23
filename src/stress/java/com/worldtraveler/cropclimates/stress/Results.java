package com.worldtraveler.cropclimates.stress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/** Where harness output lands: {@code <game dir>/stress-results/}. */
public final class Results {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Results() {
    }

    public static Path dir() {
        Path dir = FMLPaths.GAMEDIR.get().resolve("stress-results");
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return dir;
    }

    public static Path writeJson(String name, JsonElement json) {
        return writeText(name + ".json", GSON.toJson(json));
    }

    public static Path writeText(String fileName, String text) {
        Path file = dir().resolve(fileName);
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.error("ccstress: could not write {}", file, ex);
        }
        return file;
    }

    /** A timestamped line in {@code stress-results/harness.log} and the server log. */
    public static void log(String line) {
        LOGGER.info("ccstress: {}", line);
        try {
            Files.writeString(dir().resolve("harness.log"), LocalDateTime.now() + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    public static String compact(JsonElement json) {
        return new Gson().toJson(json);
    }
}
