package com.railway.works.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.railway.works.domain.BlockRelease;

public interface BlockReleaseRepository extends JpaRepository<BlockRelease, Long> {

    List<BlockRelease> findAllByOrderBySubmittedAtDescIdDesc();

    List<BlockRelease> findAllByWorkOrderIdOrderBySubmittedAtDescIdDesc(Long workOrderId);

    /** 列表筛选：线路（经区段表关联）、区段、交接状态至少给一个时生效 */
    @Query("""
            select r from BlockRelease r
            where (:lineId is null or r.sectionId in
                    (select s.id from Section s where s.lineId = :lineId))
              and (:sectionId is null or r.sectionId = :sectionId)
              and (:status is null or r.status = :status)
            order by r.submittedAt desc, r.id desc
            """)
    List<BlockRelease> search(@Param("lineId") Long lineId,
                              @Param("sectionId") Long sectionId,
                              @Param("status") String status);

    /** 同一作业单是否存在尚未终结（待复核 / 已退回）的交接单，已通过 / 已失效不阻塞 */
    @Query("""
            select count(r) from BlockRelease r
            where r.workOrderId = :workOrderId and r.status in ('PENDING', 'REJECTED')
            """)
    long countOpenByWorkOrderId(@Param("workOrderId") Long workOrderId);
}
