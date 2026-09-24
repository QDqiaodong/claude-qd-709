package com.railway.works.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import lombok.Data;

/** 各模块给页面用的出参视图。 */
public final class Views {

    private Views() {
    }

    /** 线路 */
    @Data
    public static class LineView {
        private Long id;
        private String name;
        private String startStation;
        private String endStation;
        private BigDecimal mileageKm;
        private String direction;
        private String directionName;
        private String bureau;
        private Integer sectionCount;
        private Integer defectCount;
        private Integer openDefectCount;
    }

    /** 区段 */
    @Data
    public static class SectionView {
        private Long id;
        private Long lineId;
        private String lineName;
        private String sectionNo;
        private BigDecimal startMile;
        private BigDecimal endMile;
        private String startMileText;
        private String endMileText;
        private BigDecimal lengthKm;
        private String railType;
        private String railTypeName;
        private String bedType;
        private String bedTypeName;
        private Integer designSpeed;
        private String status;
        private String statusName;
        private String statusColor;
        private Integer speedLimit;
        private String remark;
        private Integer defectCount;
        private Integer openDefectCount;
        private Integer deviceCount;
        /** 该区段最严重的未销号病害等级，用于里程条着色 */
        private String worstLevel;
        private String worstLevelName;
    }

    /** 设备（五种子类共用一张视图，专有字段按类型填充） */
    @Data
    public static class DeviceView {
        private Long id;
        private String deviceNo;
        private Long sectionId;
        private String sectionNo;
        private String lineName;
        private BigDecimal mileage;
        private String mileageText;
        private LocalDate installDate;
        private LocalDate lastCheckDate;
        private Long daysSinceCheck;
        private Boolean checkOverdue;
        private String status;
        private String statusName;
        private String typeCode;
        private String typeName;
        private String specText;
        private String remark;

        // ---- 钢轨 ----
        private String railType;
        private BigDecimal railLength;
        private BigDecimal lockTemp;
        // ---- 道岔 ----
        private String switchModel;
        private String direction;
        private String machineModel;
        // ---- 轨枕 ----
        private String sleeperType;
        private Integer sleeperCount;
        // ---- 桥梁 ----
        private BigDecimal bridgeLength;
        private String spanLayout;
        private BigDecimal loadLimit;
        // ---- 隧道 ----
        private BigDecimal tunnelLength;
        private String liningType;
        private String lighting;
    }

    /** 检查单 */
    @Data
    public static class InspectionView {
        private Long id;
        private String inspectNo;
        private Long sectionId;
        private String sectionNo;
        private String lineName;
        private String inspector;
        private LocalDate inspectDate;
        private String inspectType;
        private String inspectTypeName;
        private String remark;
        private Integer defectCount;
        private Integer openDefectCount;
    }

    /** 病害 */
    @Data
    public static class DefectView {
        private Long id;
        private Long inspectionId;
        private String inspectNo;
        private Long sectionId;
        private String sectionNo;
        private String lineName;
        private String defectType;
        private String defectTypeName;
        private String severity;
        private String severityName;
        private String severityColor;
        private BigDecimal mileage;
        private String mileageText;
        private String description;
        private LocalDate foundDate;
        private Boolean closed;
        private String closedName;
        private LocalDate closeDate;
        private String treatment;
        private Integer limitDays;
        private Long overdueDays;
    }

    /** 养修作业单 */
    @Data
    public static class WorkOrderView {
        private Long id;
        private String orderNo;
        private Long sectionId;
        private String sectionNo;
        private String lineName;
        private String workItem;
        private String workItemName;
        private LocalDate windowDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String windowText;
        private Long windowMinutes;
        private String leader;
        private Integer workerCount;
        private String cooperateUnit;
        private String status;
        private String statusName;
        private String nextStatusName;
    }

    /** 物资 */
    @Data
    public static class MaterialView {
        private Long id;
        private String name;
        private String spec;
        private String unit;
        private Integer stockQty;
        private Integer safetyStock;
        private Boolean low;
        private Integer gap;
    }

    /** 机具 */
    @Data
    public static class EquipmentView {
        private Long id;
        private String equipNo;
        private String name;
        private String model;
        private LocalDate lastMaintainDate;
        private LocalDate nextMaintainDate;
        private Long daysToMaintain;
        private Boolean dueSoon;
        private String status;
        private String statusName;
    }

    /** 顶部计数器 */
    @Data
    public static class Overview {
        private Integer lineCount;
        private Integer sectionCount;
        private Integer deviceCount;
        private Integer defectOpen;
        private Integer defectHeavy;
        private Integer workOrderActive;
        private Integer materialLow;
        private Integer equipmentDue;
    }

    /** 线路里程看板：一条线的区段 + 病害，前端据此画里程条 */
    @Data
    public static class BoardView {
        private LineView line;
        private List<SectionView> sections;
        private List<DefectView> defects;
        private BigDecimal rangeStart;
        private BigDecimal rangeEnd;
        private String rangeStartText;
        private String rangeEndText;
    }
}
