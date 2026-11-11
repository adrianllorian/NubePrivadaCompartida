package com.nubeprivada.compartida.repo;

import com.nubeprivada.compartida.domain.Activity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    @Query("select a from Activity a where (:user is null or a.username = :user) and (:action is null or a.action = :action) order by a.createdAt desc")
    Page<Activity> search(@Param("user") String user, @Param("action") String action, Pageable pageable);

    @Query("select distinct a.action from Activity a order by a.action")
    List<String> distinctActions();
}
