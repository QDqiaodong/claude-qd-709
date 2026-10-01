package com.railway.works.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 封锁解除交接处理轨迹：申请的每一次 提交 / 退回 / 重提 / 复核结论 / 失效 各落一条，
 * 保留时间、人员与说明，页面按时间正序展示完整处理轨迹。
 */
@Data
@Entity
@Table(name = "block_release_log")
public class BlockReleaseLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /** SUBMIT 提交 / RESUBMIT 重提 / REJECT 退回 / APPROVE 复核通过 / INVALIDATE 失效 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** 操作人 */
    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "acted_at", nullable = false)
    private LocalDateTime actedAt;

    /** 说明（退回意见 / 复核意见 / 失效变化项） */
    @Column(name = "remark", length = 1000)
    private String remark;

    /** 该动作之后申请所处的状态 */
    @Column(name = "result_status", nullable = false, length = 16)
    private String resultStatus;
}
