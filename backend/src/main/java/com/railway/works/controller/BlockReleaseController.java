package com.railway.works.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.railway.works.common.ApiResponse;
import com.railway.works.dto.BlockReleaseDtos;
import com.railway.works.dto.Views;
import com.railway.works.service.BlockReleaseService;

import jakarta.validation.Valid;

/** 封锁解除交接台：发起、重提、复核（通过/退回），支持按线路、区段、交接状态筛选。 */
@RestController
@RequestMapping("/api/block-releases")
public class BlockReleaseController {

    private final BlockReleaseService blockReleaseService;

    public BlockReleaseController(BlockReleaseService blockReleaseService) {
        this.blockReleaseService = blockReleaseService;
    }

    @GetMapping
    public ApiResponse<List<Views.BlockReleaseView>> list(
            @RequestParam(value = "lineId", required = false) Long lineId,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.success(blockReleaseService.list(lineId, sectionId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.BlockReleaseView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(blockReleaseService.detail(id));
    }

    /** 施工负责人发起封锁解除申请 */
    @PostMapping
    public ApiResponse<Views.BlockReleaseView> submit(@Valid @RequestBody BlockReleaseDtos.ReleaseSubmitReq req) {
        return ApiResponse.success("封锁解除申请已提交，等待复核", blockReleaseService.submit(req));
    }

    /** 已退回申请按最新现场重新提交 */
    @PostMapping("/{id}/resubmit")
    public ApiResponse<Views.BlockReleaseView> resubmit(@PathVariable("id") Long id,
                                                        @Valid @RequestBody BlockReleaseDtos.ReleaseSubmitReq req) {
        return ApiResponse.success("已按最新现场重新提交", blockReleaseService.resubmit(id, req));
    }

    /** 复核人逐项确认通过或退回整改（并发下只有一份结论生效） */
    @PostMapping("/{id}/review")
    public ApiResponse<Views.BlockReleaseView> review(@PathVariable("id") Long id,
                                                      @Valid @RequestBody BlockReleaseDtos.ReleaseReviewReq req) {
        String ok = "APPROVE".equals(req.getAction()) ? "复核通过，作业已销号，区段状态已按结论更新" : "已退回整改";
        return ApiResponse.success(ok, blockReleaseService.review(id, req));
    }
}
