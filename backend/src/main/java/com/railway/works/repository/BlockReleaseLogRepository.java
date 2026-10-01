package com.railway.works.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.railway.works.domain.BlockReleaseLog;

public interface BlockReleaseLogRepository extends JpaRepository<BlockReleaseLog, Long> {

    List<BlockReleaseLog> findAllByReleaseIdOrderByActedAtAscIdAsc(Long releaseId);
}
