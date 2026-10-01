package com.railway.works.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 封锁解除交接台入参。 */
public final class BlockReleaseDtos {

    private BlockReleaseDtos() {
    }

    /** 施工负责人发起 / 退回后重新提交 */
    @Data
    public static class ReleaseSubmitReq {
        @NotNull(message = "请选择要解除封锁的养修作业单")
        private Long workOrderId;

        @NotBlank(message = "现场清障结果必须逐项填写")
        private String siteClear;

        @NotBlank(message = "人员撤离情况必须逐项填写")
        private String staffWithdrawn;

        @NotBlank(message = "机具撤出情况必须逐项填写")
        private String equipmentRemoved;

        @NotBlank(message = "线路复测结果必须逐项填写")
        private String retestResult;

        /** NORMAL 恢复正常 / SPEED_LIMIT 继续限速 / BLOCKED 继续封锁 */
        @NotBlank(message = "请给出恢复正常、继续限速或继续封锁的建议")
        private String suggestion;

        /** 建议限速时的限速值，不得高于设计时速 */
        private Integer suggestedSpeed;

        /** 建议限速时的原因 */
        private String limitReason;

        @NotBlank(message = "缺少施工负责人")
        private String leader;
    }

    /** 复核人逐项确认（通过）或退回整改 */
    @Data
    public static class ReleaseReviewReq {
        @NotBlank(message = "缺少复核人")
        private String reviewer;

        /** APPROVE 通过 / REJECT 退回整改 */
        @NotBlank(message = "请选择复核通过或退回整改")
        private String action;

        /** 退回时必填整改意见 */
        private String comment;

        /** 通过时的最终结论：NORMAL / SPEED_LIMIT / BLOCKED（逐项确认后的最终意见） */
        private String decision;

        /** 结论为限速时的最终限速值，不得高于设计时速 */
        private Integer finalSpeed;

        /** 结论为限速时的原因 */
        private String finalReason;
    }
}
