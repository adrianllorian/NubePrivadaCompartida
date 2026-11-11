package com.nubeprivada.compartida.web;

import com.nubeprivada.compartida.domain.FileItem;
import com.nubeprivada.compartida.repo.FileItemRepository;
import com.nubeprivada.compartida.service.ActivityService;
import com.nubeprivada.compartida.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
public class ShareController {

    private final FileItemRepository items;
    private final StorageService storage;
    private final ActivityService activity;

    public ShareController(FileItemRepository items, StorageService storage, ActivityService activity) {
        this.items = items;
        this.storage = storage;
        this.activity = activity;
    }

    @GetMapping("/s/{token}")
    public void download(@PathVariable String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileItem item = items.findByShareToken(token).orElse(null);
        if (item == null) {
            response.sendError(404);
            return;
        }

        if (item.isFolder()) {
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + encode(item.getName() + ".zip"));
            storage.writeZip(List.of(item), response.getOutputStream());
            activity.log("(enlace)", "DESCARGA", "Descargó la carpeta " + item.getName() + " por enlace", request);
            return;
        }

        Path path = storage.physicalPath(item);
        if (!Files.exists(path)) {
            response.sendError(404);
            return;
        }

        response.setContentType(item.getContentType() == null ? "application/octet-stream" : item.getContentType());
        response.setContentLengthLong(Files.size(path));
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encode(item.getName()));
        try (InputStream in = Files.newInputStream(path); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
        activity.log("(enlace)", "DESCARGA", "Descargó por enlace " + item.getName(), request);
    }

    private String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
