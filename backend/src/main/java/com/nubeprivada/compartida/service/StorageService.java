package com.nubeprivada.compartida.service;

import com.nubeprivada.compartida.domain.FileItem;
import com.nubeprivada.compartida.repo.FileItemRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final FileItemRepository files;
    private final Path home;
    private final Path root;
    private final Path uploads;
    private final Path thumbs;

    public StorageService(FileItemRepository files, @Value("${cloud.home}") String home) {
        this.files = files;
        this.home = Paths.get(home).toAbsolutePath().normalize();
        this.root = this.home.resolve("files");
        this.uploads = this.home.resolve("uploads");
        this.thumbs = this.home.resolve("thumbs");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root);
            Files.createDirectories(home.resolve("tmp"));
            Files.createDirectories(uploads);
            Files.createDirectories(thumbs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path getHome() {
        return home;
    }

    public Path getUploadsDir() {
        return uploads;
    }

    public List<FileItem> children(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return files.findByParentIdIsNullOrderByFolderDescNameAsc();
        }
        return files.findByParentIdOrderByFolderDescNameAsc(parentId);
    }

    public List<FileItem> breadcrumb(String parentId) {
        List<FileItem> chain = new ArrayList<>();
        String cursor = parentId;
        int guard = 0;
        while (cursor != null && !cursor.isBlank() && guard++ < 100) {
            Optional<FileItem> item = files.findById(cursor);
            if (item.isEmpty()) {
                break;
            }
            chain.add(0, item.get());
            cursor = item.get().getParentId();
        }
        return chain;
    }

    public String pathOf(FileItem item) {
        List<String> parts = new ArrayList<>();
        String cursor = item.getParentId();
        int guard = 0;
        while (cursor != null && !cursor.isBlank() && guard++ < 100) {
            Optional<FileItem> parent = files.findById(cursor);
            if (parent.isEmpty()) {
                break;
            }
            parts.add(0, parent.get().getName());
            cursor = parent.get().getParentId();
        }
        parts.add(item.getName());
        return String.join("/", parts);
    }

    public FileItem createFolder(String parentId, String name, String owner) {
        String clean = cleanName(name);
        if (clean.isBlank()) {
            throw new IllegalArgumentException("Nombre de carpeta vacío");
        }
        FileItem item = new FileItem();
        item.setId(UUID.randomUUID().toString());
        item.setParentId(emptyToNull(parentId));
        item.setFolder(true);
        item.setName(uniqueName(parentId, clean, null));
        item.setOwner(owner);
        item.setSize(0);
        item.setCreatedAt(Instant.now());
        return files.save(item);
    }

    public FileItem store(String parentId, MultipartFile file, String owner) throws IOException {
        String clean = cleanName(file.getOriginalFilename());
        if (clean.isBlank()) {
            clean = "fichero";
        }
        FileItem item = new FileItem();
        item.setId(UUID.randomUUID().toString());
        item.setParentId(emptyToNull(parentId));
        item.setFolder(false);
        item.setName(uniqueName(parentId, clean, null));
        item.setOwner(owner);
        item.setSize(file.getSize());
        item.setContentType(file.getContentType());
        item.setCreatedAt(Instant.now());
        item.setStoredName(item.getId() + ".bin");

        Path target = root.resolve(item.getStoredName());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (file.getSize() <= 0) {
            item.setSize(Files.size(target));
        }
        return files.save(item);
    }

    public FileItem storeFromTemp(String parentId, String name, String owner, String contentType, Path temp, long size) throws IOException {
        String clean = cleanName(name);
        if (clean.isBlank()) {
            clean = "fichero";
        }
        FileItem item = new FileItem();
        item.setId(UUID.randomUUID().toString());
        item.setParentId(emptyToNull(parentId));
        item.setFolder(false);
        item.setName(uniqueName(parentId, clean, null));
        item.setOwner(owner);
        item.setSize(size);
        item.setContentType(contentType);
        item.setCreatedAt(Instant.now());
        item.setStoredName(item.getId() + ".bin");
        Files.move(temp, root.resolve(item.getStoredName()), StandardCopyOption.REPLACE_EXISTING);
        return files.save(item);
    }

    public Path physicalPath(FileItem item) {
        if (item.isFolder() || item.getStoredName() == null) {
            throw new IllegalArgumentException("No es un fichero");
        }
        return root.resolve(item.getStoredName());
    }

    public void delete(FileItem item) {
        List<FileItem> kids = files.findByParentId(item.getId());
        for (FileItem kid : kids) {
            delete(kid);
        }
        if (!item.isFolder() && item.getStoredName() != null) {
            try {
                Files.deleteIfExists(root.resolve(item.getStoredName()));
                Files.deleteIfExists(thumbs.resolve(item.getId() + ".jpg"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.delete(item);
    }

    public String shareToken(FileItem item) {
        if (item.getShareToken() == null) {
            item.setShareToken(UUID.randomUUID().toString().replace("-", ""));
            files.save(item);
        }
        return item.getShareToken();
    }

    public FileItem rename(FileItem item, String newName) {
        String clean = cleanName(newName);
        if (clean.isBlank()) {
            throw new IllegalArgumentException("Nombre vacío");
        }
        item.setName(uniqueName(item.getParentId(), clean, item.getId()));
        return files.save(item);
    }

    public FileItem move(FileItem item, String newParentId) {
        String target = emptyToNull(newParentId);
        if (target != null && !isFolder(target)) {
            throw new IllegalArgumentException("El destino no es una carpeta");
        }
        if (item.isFolder() && target != null && (target.equals(item.getId()) || isDescendant(target, item.getId()))) {
            throw new IllegalArgumentException("No se puede mover una carpeta dentro de sí misma");
        }
        item.setParentId(target);
        item.setName(uniqueName(target, item.getName(), item.getId()));
        return files.save(item);
    }

    public boolean isDescendant(String candidateId, String ancestorId) {
        String cursor = candidateId;
        int guard = 0;
        while (cursor != null && !cursor.isBlank() && guard++ < 100) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            Optional<FileItem> current = files.findById(cursor);
            if (current.isEmpty()) {
                return false;
            }
            cursor = current.get().getParentId();
        }
        return false;
    }

    public void writeZip(List<FileItem> items, OutputStream out) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        for (FileItem item : items) {
            addToZip(item, "", zos);
        }
        zos.finish();
        zos.flush();
    }

    private void addToZip(FileItem item, String prefix, ZipOutputStream zos) throws IOException {
        String path = prefix.isEmpty() ? item.getName() : prefix + "/" + item.getName();
        zos.putNextEntry(new ZipEntry(path + (item.isFolder() ? "/" : "")));
        if (item.isFolder()) {
            zos.closeEntry();
            for (FileItem kid : files.findByParentId(item.getId())) {
                addToZip(kid, path, zos);
            }
            return;
        }
        Path file = physicalPath(item);
        if (Files.exists(file)) {
            Files.copy(file, zos);
        }
        zos.closeEntry();
    }

    public Path thumbnail(FileItem item) {
        Path cached = thumbs.resolve(item.getId() + ".jpg");
        if (Files.exists(cached)) {
            return cached;
        }
        String name = item.getName().toLowerCase(Locale.ROOT);
        boolean image = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".bmp");
        if (!image) {
            return null;
        }
        try (InputStream in = Files.newInputStream(physicalPath(item))) {
            BufferedImage original = ImageIO.read(in);
            if (original == null) {
                log.warn("Miniatura: formato no reconocido para {}", item.getName());
                return null;
            }
            int max = 320;
            double scale = Math.min(1.0, (double) max / Math.max(original.getWidth(), original.getHeight()));
            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(original.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
            g.dispose();
            boolean written = ImageIO.write(out, "jpg", cached.toFile());
            if (!written) {
                log.warn("Miniatura: no hay escritor JPEG disponible");
                return null;
            }
            return cached;
        } catch (IOException | RuntimeException e) {
            log.warn("Miniatura: error generando miniatura de {}: {}", item.getName(), e.toString());
            return null;
        }
    }

    private boolean isFolder(String id) {
        return files.findById(id).map(FileItem::isFolder).orElse(false);
    }

    private String uniqueName(String parentId, String name, String excludeId) {
        List<FileItem> siblings = children(parentId);
        boolean taken = siblings.stream()
                .filter(f -> !f.getId().equals(excludeId))
                .anyMatch(f -> f.getName().equalsIgnoreCase(name));
        if (!taken) {
            return name;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int n = 1;
        while (true) {
            String candidate = base + " (" + n + ")" + ext;
            String c = candidate;
            boolean used = siblings.stream()
                    .filter(f -> !f.getId().equals(excludeId))
                    .anyMatch(f -> f.getName().equalsIgnoreCase(c));
            if (!used) {
                return candidate;
            }
            n++;
        }
    }

    private String cleanName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.equals(".") || name.equals("..")) {
            return "";
        }
        return name;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Stats stats() {
        List<FileItem> all = files.findAll();
        long appUsed = all.stream().filter(f -> !f.isFolder()).mapToLong(FileItem::getSize).sum();
        long fileCount = all.stream().filter(f -> !f.isFolder()).count();
        try {
            FileStore store = Files.getFileStore(home);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            return new Stats(total, total - free, free, appUsed, fileCount);
        } catch (IOException e) {
            return new Stats(0, 0, 0, appUsed, fileCount);
        }
    }

    public record Stats(long total, long used, long free, long appUsed, long fileCount) {
    }
}
