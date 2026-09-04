package com.fherrmann.shopping.repository;

import com.fherrmann.shopping.model.ShoppingData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Liest und schreibt {@code shopping.json}. Gibt es die Datei noch nicht, faengt alles leer an. */
@Repository
public class ShoppingRepository {

    private final Path dataFile;
    private final ObjectMapper objectMapper;

    public ShoppingRepository(
            @Value("${shopping.data-file:data/shopping.json}") String dataFile,
            ObjectMapper objectMapper) {
        this.dataFile = Path.of(dataFile);
        this.objectMapper = objectMapper;
    }

    public synchronized ShoppingData load() {
        if (!Files.exists(dataFile)) {
            return ShoppingData.empty();
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(dataFile), ShoppingData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read shopping file: " + dataFile, e);
        }
    }

    /** Erst daneben schreiben, dann umbenennen - eine halbe Datei waere die ganze Liste. */
    public synchronized void save(ShoppingData data) {
        try {
            if (dataFile.getParent() != null) {
                Files.createDirectories(dataFile.getParent());
            }
            Path temp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), data);
            Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write shopping file: " + dataFile, e);
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
