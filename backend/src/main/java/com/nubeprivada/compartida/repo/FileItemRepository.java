package com.nubeprivada.compartida.repo;

import com.nubeprivada.compartida.domain.FileItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileItemRepository extends JpaRepository<FileItem, String> {

    List<FileItem> findByParentIdOrderByFolderDescNameAsc(String parentId);

    List<FileItem> findByParentIdIsNullOrderByFolderDescNameAsc();

    Optional<FileItem> findByShareToken(String shareToken);

    List<FileItem> findByParentId(String parentId);

    List<FileItem> findByFolderTrue();

    List<FileItem> findByNameContainingIgnoreCaseOrderByFolderDescNameAsc(String name);
}
