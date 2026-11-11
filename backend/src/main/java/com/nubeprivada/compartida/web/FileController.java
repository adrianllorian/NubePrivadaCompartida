package com.nubeprivada.compartida.web;

import com.nubeprivada.compartida.domain.FileItem;
import com.nubeprivada.compartida.repo.FileItemRepository;
import com.nubeprivada.compartida.service.ActivityService;
import com.nubeprivada.compartida.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FileController {

    private final FileItemRepository items;
    private final StorageService storage;
    private final ActivityService activity;

    public FileController(FileItemRepository items, StorageService storage, ActivityService activity) {
        this.items = items;
        this.storage = storage;
        this.activity = activity;
    }

    public record ItemDto(String id, String name, boolean folder, long size, String owner,
                          String createdAt, String contentType, String shareToken, String path) {
        static ItemDto of(FileItem item, String path) {
            return new ItemDto(item.getId(), item.getName(), item.isFolder(), item.getSize(), item.getOwner(),
                    item.getCreatedAt() == null ? "" : item.getCreatedAt().toString(),
                    item.getContentType(), item.getShareToken(), path);
        }
    }

    public record Listing(String parentId, List<ItemDto> breadcrumb, List<ItemDto> items, boolean search) {
    }

    public record FolderRequest(String parentId, String name) {
    }

    public record ShareResponse(String token, String url) {
    }

    public record IdsRequest(List<String> ids) {
    }

    public record RenameRequest(String name) {
    }

    public record MoveRequest(String parentId) {
    }

    public record FolderDto(String id, String name, String path) {
    }

    @GetMapping("/files")
    public Listing list(@RequestParam(required = false) String parentId,
                        @RequestParam(required = false) String q) {
        if (q != null && !q.isBlank()) {
            List<ItemDto> found = items.findByNameContainingIgnoreCaseOrderByFolderDescNameAsc(q.trim()).stream()
                    .map(i -> ItemDto.of(i, storage.pathOf(i)))
                    .toList();
            return new Listing(parentId, List.of(), found, true);
        }
        List<ItemDto> crumbs = storage.breadcrumb(parentId).stream()
                .map(i -> ItemDto.of(i, storage.pathOf(i)))
                .toList();
        List<ItemDto> children = storage.children(parentId).stream()
                .map(i -> ItemDto.of(i, null))
                .toList();
        return new Listing(parentId, crumbs, children, false);
    }

    @GetMapping("/folders")
    public List<FolderDto> folders() {
        return items.findByFolderTrue().stream()
                .map(f -> new FolderDto(f.getId(), f.getName(), storage.pathOf(f)))
                .sorted((a, b) -> a.path().compareToIgnoreCase(b.path()))
                .toList();
    }

    @PostMapping("/folder")
    public ResponseEntity<?> createFolder(@RequestBody FolderRequest body, HttpServletRequest request) {
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name required"));
        }
        FileItem folder = storage.createFolder(body.parentId(), name, currentUser());
        activity.log(currentUser(), "CARPETA", "Creó la carpeta " + folder.getName(), request);
        return ResponseEntity.ok(ItemDto.of(folder, storage.pathOf(folder)));
    }

    @PostMapping("/files/upload")
    public ResponseEntity<?> upload(@RequestParam(required = false) String parentId,
                                    @RequestParam("files") MultipartFile[] files,
                                    HttpServletRequest request) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "no files"));
        }
        List<ItemDto> saved = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                if (file.isEmpty() && file.getSize() == 0) {
                    continue;
                }
                FileItem item = storage.store(parentId, file, currentUser());
                saved.add(ItemDto.of(item, null));
                activity.log(currentUser(), "SUBIDA", "Subió " + item.getName() + " (" + item.getSize() + " bytes)", request);
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "write failed"));
        }
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/files/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id, HttpServletRequest request) {
        FileItem item = items.findById(id).orElse(null);
        if (item == null || item.isFolder()) {
            return ResponseEntity.notFound().build();
        }
        Path path = storage.physicalPath(item);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        activity.log(currentUser(), "DESCARGA", "Descargó " + item.getName(), request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(item.getName()))
                .contentLength(sizeOf(item, path))
                .body(new FileSystemResource(path));
    }

    @GetMapping("/files/{id}/raw")
    public ResponseEntity<?> raw(@PathVariable String id,
                                 @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        FileItem item = items.findById(id).orElse(null);
        if (item == null || item.isFolder()) {
            return ResponseEntity.notFound().build();
        }
        Path path = storage.physicalPath(item);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        long length = sizeOf(item, path);
        MediaType type = mediaType(item);
        FileSystemResource resource = new FileSystemResource(path);

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (!ranges.isEmpty()) {
                    HttpRange range = ranges.get(0);
                    long start = range.getRangeStart(length);
                    long end = range.getRangeEnd(length);
                    long count = end - start + 1;
                    InputStream slice = slice(path, start, count);
                    return ResponseEntity.status(206)
                            .contentType(type)
                            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                            .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                            .contentLength(count)
                            .body(new InputStreamResource(slice));
                }
            } catch (IllegalArgumentException | IOException ignored) {
                // Range inválido: se sirve el fichero completo
            }
        }

        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, inline(item.getName()))
                .contentLength(length)
                .body(resource);
    }

    @GetMapping("/files/{id}/thumb")
    public ResponseEntity<Resource> thumb(@PathVariable String id) {
        FileItem item = items.findById(id).orElse(null);
        if (item == null || item.isFolder()) {
            return ResponseEntity.notFound().build();
        }
        Path thumb = storage.thumbnail(item);
        if (thumb == null || !Files.exists(thumb)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                .body(new FileSystemResource(thumb));
    }

    @PostMapping("/files/{id}/rename")
    public ResponseEntity<?> rename(@PathVariable String id, @RequestBody RenameRequest body, HttpServletRequest request) {
        FileItem item = items.findById(id).orElse(null);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canDelete(item)) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }
        try {
            FileItem updated = storage.rename(item, body.name());
            activity.log(currentUser(), "RENOMBRE", "Renombró " + item.getName() + " a " + updated.getName(), request);
            return ResponseEntity.ok(ItemDto.of(updated, storage.pathOf(updated)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid name"));
        }
    }

    @PostMapping("/files/{id}/move")
    public ResponseEntity<?> move(@PathVariable String id, @RequestBody MoveRequest body, HttpServletRequest request) {
        FileItem item = items.findById(id).orElse(null);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canDelete(item)) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }
        try {
            FileItem updated = storage.move(item, body.parentId());
            activity.log(currentUser(), "MOVIMIENTO", "Movió " + updated.getName(), request);
            return ResponseEntity.ok(ItemDto.of(updated, storage.pathOf(updated)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest request) {
        FileItem item = items.findById(id).orElse(null);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canDelete(item)) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }
        activity.log(currentUser(), "BORRADO", "Borró " + item.getName(), request);
        storage.delete(item);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/files/batch-delete")
    public ResponseEntity<?> batchDelete(@RequestBody IdsRequest body, HttpServletRequest request) {
        int deleted = 0;
        List<String> denied = new ArrayList<>();
        if (body != null && body.ids() != null) {
            for (String id : body.ids()) {
                FileItem item = items.findById(id).orElse(null);
                if (item == null) {
                    continue;
                }
                if (!canDelete(item)) {
                    denied.add(item.getName());
                    continue;
                }
                storage.delete(item);
                deleted++;
            }
        }
        activity.log(currentUser(), "BORRADO", "Borró " + deleted + " elemento(s)", request);
        return ResponseEntity.ok(Map.of("deleted", deleted, "denied", denied));
    }

    @GetMapping("/files/zip")
    public void zip(@RequestParam("ids") List<String> ids, HttpServletResponse response, HttpServletRequest request) throws IOException {
        List<FileItem> selected = new ArrayList<>();
        for (String id : ids) {
            items.findById(id).ifPresent(selected::add);
        }
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ficheros.zip\"");
        storage.writeZip(selected, response.getOutputStream());
        activity.log(currentUser(), "DESCARGA", "Descargó " + selected.size() + " elemento(s) en ZIP", request);
    }

    @PostMapping("/files/{id}/share")
    public ResponseEntity<?> share(@PathVariable String id, HttpServletRequest request) {
        FileItem item = items.findById(id).orElse(null);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        String token = storage.shareToken(item);
        activity.log(currentUser(), "ENLACE", "Creó enlace para " + item.getName(), request);
        String base = baseUrl(request);
        return ResponseEntity.ok(new ShareResponse(token, base + "/s/" + token));
    }

    @GetMapping("/stats")
    public StorageService.Stats stats() {
        return storage.stats();
    }

    private long sizeOf(FileItem item, Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return item.getSize();
        }
    }

    private InputStream slice(Path path, long start, long count) throws IOException {
        InputStream input = Files.newInputStream(path);
        input.skipNBytes(start);
        return new FilterInputStream(input) {
            private long remaining = count;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int value = super.read();
                if (value >= 0) {
                    remaining--;
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int read = super.read(buffer, offset, (int) Math.min(length, remaining));
                if (read > 0) {
                    remaining -= read;
                }
                return read;
            }
        };
    }

    private MediaType mediaType(FileItem item) {
        if (item.getContentType() != null && !item.getContentType().isBlank()) {
            try {
                return MediaType.parseMediaType(item.getContentType());
            } catch (Exception ignored) {
                // continúa con la extensión
            }
        }
        String name = item.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) return MediaType.valueOf("video/mp4");
        if (name.endsWith(".webm")) return MediaType.valueOf("video/webm");
        if (name.endsWith(".mp3")) return MediaType.valueOf("audio/mpeg");
        if (name.endsWith(".m4a")) return MediaType.valueOf("audio/mp4");
        if (name.endsWith(".ogg")) return MediaType.valueOf("audio/ogg");
        if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (name.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".log")) return MediaType.TEXT_PLAIN;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String attachment(String name) {
        return "attachment; filename*=UTF-8''" + encode(name);
    }

    private String inline(String name) {
        return "inline; filename*=UTF-8''" + encode(name);
    }

    private String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String baseUrl(HttpServletRequest request) {
        String proto = header(request, "X-Forwarded-Proto", request.getScheme());
        String host = header(request, "X-Forwarded-Host", request.getHeader("Host"));
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return proto + "://" + host;
    }

    private String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.split(",")[0].trim();
    }

    private boolean canDelete(FileItem item) {
        return isAdmin() || currentUser().equals(item.getOwner());
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
