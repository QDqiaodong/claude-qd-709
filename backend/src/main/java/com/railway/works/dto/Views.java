package com.railway.works.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /** 封锁解除交接单 */
    @Data
    public static class BlockReleaseView {
        private Long id;
        private String releaseNo;
        private Long workOrderId;
        private String orderNo;
        private String workItem;
        private String workItemName;
        private Long sectionId;
        private String sectionNo;
        private Long lineId;
        private String lineName;

        // 现场四项
        private String siteClear;
        private String staffWithdrawn;
        private String equipmentRemoved;
        private String retestResult;

        // 施工负责人建议
        private String suggestion;
        private String suggestionName;
        private Integer suggestedSpeed;
        private String limitReason;

        private String leader;
        private String reviewer;
        private String status;
        private String statusName;

        // 发起时快照
        private String snapshotWorkStatus;
        private String snapshotWorkStatusName;
        private String snapshotSectionStatus;
        private String snapshotSectionStatusName;
        private Integer snapshotSectionSpeed;
        private List<Long> snapshotHeavyDefectIds;
        private String snapshotHeavyDefectText;

        // 复核结论
        private String finalDecision;
        private String finalDecisionName;
        private Integer finalSpeed;
        private String finalReason;

        private LocalDateTime submittedAt;
        private LocalDateTime reviewedAt;
        private LocalDateTime invalidatedAt;
        private String invalidReason;
        private Long version;

        /** 发起时作业 / 区段 / 重级病害与当前现场的逐项差异（空表示现场未变化） */
        private List<ReleaseChangeView> changes;
        /** 当前现场是否仍存在未销号重级病害（控制页面上能不能给“恢复正常”） */
        private Boolean currentHeavyOpen;
        private Integer currentDesignSpeed;

        private List<ReleaseLogView> logs;
    }

    /** 交接单失效变化项：作业状态 / 区段状态 / 重级病害销号情况 */
    @Data
    public static class ReleaseChangeView {
        /** WORK_STATUS / SECTION_STATUS / HEAVY_DEFECT */
        private String changeType;
        private String changeTypeName;
        private String snapshotText;
        private String currentText;
        /** 是否确实发生了变化（false 表示这一项与发起时一致） */
        private Boolean changed;
    }

    /** 交接处理轨迹一条 */
    @Data
    public static class ReleaseLogView {
        private Long id;
        private String action;
        private String actionName;
        private String operator;
        private LocalDateTime actedAt;
        private String remark;
        private String resultStatus;
        private String resultStatusName;
    }

    /** 顶部计数器 */
    @Data
    public static class Overview {        private Integer lineCount;
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
