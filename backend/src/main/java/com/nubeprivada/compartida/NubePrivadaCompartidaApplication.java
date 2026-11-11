package com.nubeprivada.compartida;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NubePrivadaCompartidaApplication {

    public static void main(String[] args) {
        Path carpeta = prepararCarpetaDeDatos();
        System.setProperty("app.data.dir", carpeta.toString().replace('\\', '/'));
        SpringApplication.run(NubePrivadaCompartidaApplication.class, args);
    }

    private static Path prepararCarpetaDeDatos() {
        String override = System.getenv("CLOUD_HOME");
        if (override == null || override.isBlank()) {
            override = System.getProperty("app.data.dir");
        }

        Path carpeta = (override != null && !override.isBlank())
                ? Paths.get(override)
                : carpetaJuntoAlJar();

        carpeta = carpeta.toAbsolutePath().normalize();
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
        return carpeta;
    }

    private static Path carpetaJuntoAlJar() {
        String classpath = System.getProperty("java.class.path");
        if (classpath != null) {
            for (String entrada : classpath.split(File.pathSeparator)) {
                if (entrada.toLowerCase().endsWith(".jar")) {
                    Path jar = Paths.get(entrada).toAbsolutePath().normalize();
                    if (Files.isRegularFile(jar)) {
                        return jar.getParent().resolve("data");
                    }
                }
            }
        }

        try {
            Path code = Paths.get(NubePrivadaCompartidaApplication.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (Files.isRegularFile(code)) {
                return code.getParent().resolve("data");
            }
        } catch (Exception ignored) {
            // En desarrollo se usa la carpeta de trabajo.
        }

        return Paths.get(System.getProperty("user.dir"), "data");
    }
}
