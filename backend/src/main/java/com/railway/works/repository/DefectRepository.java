package com.railway.works.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.railway.works.domain.Defect;

public interface DefectRepository extends JpaRepository<Defect, Long> {

    List<Defect> findAllByOrderByFoundDateDesc();

    List<Defect> findAllBySectionIdOrderByMileageAsc(Long sectionId);

    List<Defect> findAllByInspectionIdOrderByMileageAsc(Long inspectionId);

    List<Defect> findAllBySeverityOrderByFoundDateAsc(String severity);

    List<Defect> findAllByClosedOrderByFoundDateAsc(Boolean closed);

    /** 区段内某等级的未销号病害（交接失效检测用） */
    List<Defect> findAllBySectionIdAndSeverityAndClosedFalseOrderByMileageAsc(Long sectionId, String severity);
}
