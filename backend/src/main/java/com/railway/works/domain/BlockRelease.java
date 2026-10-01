package com.railway.works.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 封锁解除交接单：把一张「已验收」养修作业、其区段当前状态、区段未销号重级病害
 * 汇总进同一条解除流程，由施工负责人发起、另一名复核人给出唯一结论。
 *
 * <p>状态：PENDING 待复核 / APPROVED 已通过 / REJECTED 已退回 / INVALID 已失效。
 *
 * <p>并发：{@link #version} 乐观锁。两名复核人同时处理时，先到的提交把版本加一，
 * 后到的提交撞 {@code ObjectOptimisticLockingFailureException}，只能看到已变化的申请，
 * 重复点击 / 网络重试都不会产生第二份结论或第二次交接落账。
 */
@Data
@Entity
@Table(name = "block_release")
public class BlockRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 交接单号，如 BR-2026-0001 */
    @Column(name = "release_no", nullable = false, length = 32)
    private String releaseNo;

    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    // ---------------- 发起时的现场四项确认（施工负责人逐项填写） ----------------

    /** 现场清障结果 */
    @Column(name = "site_clear", nullable = false, length = 255)
    private String siteClear;

    /** 人员撤离结果 */
    @Column(name = "staff_withdrawn", nullable = false, length = 255)
    private String staffWithdrawn;

    /** 机具撤出结果 */
    @Column(name = "equipment_removed", nullable = false, length = 255)
    private String equipmentRemoved;

    /** 线路复测结果 */
    @Column(name = "retest_result", nullable = false, length = 255)
    private String retestResult;

    /** 施工负责人的建议：NORMAL 恢复正常 / SPEED_LIMIT 继续限速 / BLOCKED 继续封锁 */
    @Column(name = "suggestion", nullable = false, length = 16)
    private String suggestion;

    /** 建议为限速时的限速值（不高于设计时速） */
    @Column(name = "suggested_speed")
    private Integer suggestedSpeed;

    /** 限速原因（建议限速时必填） */
    @Column(name = "limit_reason", length = 255)
    private String limitReason;

    /** 施工负责人（发起人） */
    @Column(name = "leader", nullable = false, length = 32)
    private String leader;

    /** 复核人，必须与施工负责人不是同一人 */
    @Column(name = "reviewer", length = 32)
    private String reviewer;

    /** PENDING 待复核 / APPROVED 已通过 / REJECTED 已退回 / INVALID 已失效 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    // ---------------- 发起时记住的现场快照（用于复核前失效检测） ----------------

    /** 发起时作业单状态（CHECKED） */
    @Column(name = "snapshot_work_status", nullable = false, length = 16)
    private String snapshotWorkStatus;

    /** 发起时区段状态 */
    @Column(name = "snapshot_section_status", nullable = false, length = 16)
    private String snapshotSectionStatus;

    /** 发起时区段限速值 */
    @Column(name = "snapshot_section_speed")
    private Integer snapshotSectionSpeed;

    /** 发起时未销号重级病害 id 清单，逗号分隔；空串表示当时没有 */
    @Column(name = "snapshot_heavy_defect_ids", nullable = false, length = 1000)
    private String snapshotHeavyDefectIds;

    /** 发起时未销号重级病害的文字摘要（里程 + 描述），页面直接展示 */
    @Column(name = "snapshot_heavy_defect_text", nullable = false, length = 2000)
    private String snapshotHeavyDefectText;

    // ---------------- 复核结论 ----------------

    /** 复核结论：NORMAL / SPEED_LIMIT / BLOCKED，通过时据此落区段状态 */
    @Column(name = "final_decision", length = 16)
    private String finalDecision;

    @Column(name = "final_speed")
    private Integer finalSpeed;

    @Column(name = "final_reason", length = 255)
    private String finalReason;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 失效时间 */
    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    /** 失效原因（页面明确展示是哪些变化项导致失效） */
    @Column(name = "invalid_reason", length = 500)
    private String invalidReason;

    /** 乐观锁版本：保证同一条申请只有一份复核结论生效 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
