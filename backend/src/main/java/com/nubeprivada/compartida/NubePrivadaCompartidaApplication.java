package com.nubeprivada.compartida;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NubePrivadaCompartidaApplication {

    private static final String CARPETA_POR_DEFECTO = "NubePrivadaCompartida";

    public static void main(String[] args) {
        prepararCarpetaDeDatos();
        SpringApplication.run(NubePrivadaCompartidaApplication.class, args);
    }

    private static void prepararCarpetaDeDatos() {
        String home = System.getenv("CLOUD_HOME");
        if (home == null || home.isBlank()) {
            home = Paths.get(System.getProperty("user.home"), CARPETA_POR_DEFECTO).toString();
        }

        Path carpeta = Paths.get(home).toAbsolutePath().normalize();
        try {
            Files.createDirectories(carpeta);
        } catch (IOException e) {
            System.err.println("No se pudo crear la carpeta de datos " + carpeta + ": " + e.getMessage());
        }

        // Conserva los datos de la versión anterior: cloudshare.db -> nube-privada.db
        Path anterior = carpeta.resolve("cloudshare.db");
        Path nuevo = carpeta.resolve("nube-privada.db");
        if (Files.exists(anterior) && !Files.exists(nuevo)) {
            try {
                Files.move(anterior, nuevo);
            } catch (IOException e) {
                System.err.println("No se pudo migrar la base de datos anterior: " + e.getMessage());
            }
        }
    }
}
