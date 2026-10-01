package com.railway.works.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.railway.works.common.BizException;
import com.railway.works.domain.Section;
import com.railway.works.domain.WorkOrder;
import com.railway.works.domain.WorksDict;
import com.railway.works.dto.WorkOrderDtos;
import com.railway.works.dto.Views;
import com.railway.works.repository.SectionRepository;
import com.railway.works.repository.WorkOrderRepository;

/**
 * 模块四：养修作业单。
 *
 * <p>规则面：
 * <ol>
 *   <li>作业单号唯一</li>
 *   <li>天窗点结束时刻必须晚于开始时刻，且单次不超过 {@value WorksDict#MAX_WINDOW_MINUTES} 分钟</li>
 *   <li>同一区段同一天窗日期不允许重复排程</li>
 *   <li>状态只能逐级推进：计划 → 已批复 → 施工中 → 已验收；
 *       <b>「已验收 → 已销号」必须走封锁解除交接台</b>，不允许再由此直接推进。
 *       系统升级前已经销号的历史作业单原样展示，不要求补造交接申请。</li>
 *   <li>区段处于封锁施工时不允许再排新的施工天窗</li>
 * </ol>
 */
@Service
public class WorkOrderService {

    private final WorkOrderRepository workOrderRepository;
    private final SectionRepository sectionRepository;
    private final ViewSupport viewSupport;
    private final BlockReleaseService blockReleaseService;

    public WorkOrderService(WorkOrderRepository workOrderRepository,
                            SectionRepository sectionRepository,
                            ViewSupport viewSupport,
                            BlockReleaseService blockReleaseService) {
        this.workOrderRepository = workOrderRepository;
        this.sectionRepository = sectionRepository;
        this.viewSupport = viewSupport;
        this.blockReleaseService = blockReleaseService;
    }

    @Transactional(readOnly = true)
    public List<Views.WorkOrderView> list(Long sectionId) {
        List<WorkOrder> orders = sectionId == null
                ? workOrderRepository.findAllByOrderByWindowDateAscStartTimeAsc()
                : workOrderRepository.findAllBySectionIdOrderByWindowDateAsc(sectionId);
        List<Views.WorkOrderView> list = new ArrayList<>();
        for (WorkOrder order : orders) {
            list.add(viewSupport.toView(order));
        }
        return list;
    }

    @Transactional
    public Views.WorkOrderView create(WorkOrderDtos.WorkOrderSaveReq req) {
        String orderNo = req.getOrderNo() == null ? "" : req.getOrderNo().trim();
        if (workOrderRepository.existsByOrderNo(orderNo)) {
            throw new BizException("作业单号 " + orderNo + " 已存在");
        }
        Section section = req.getSectionId() == null ? null : sectionRepository.findById(req.getSectionId()).orElse(null);
        if (section == null) {
            throw new BizException("请先选择作业所属区段");
        }
        if (!WorksDict.isValidWorkItem(req.getWorkItem())) {
            throw new BizException("作业项目只能是 起道/拨道/捣固/换轨/清筛/打磨/换枕/应力放散");
        }
        LocalTime start = req.getStartTime() == null ? LocalTime.of(0, 0) : req.getStartTime();
        LocalTime end = req.getEndTime() == null ? LocalTime.of(4, 0) : req.getEndTime();
        if (!end.isAfter(start)) {
            throw new BizException("天窗结束时刻必须晚于开始时刻");
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes > WorksDict.MAX_WINDOW_MINUTES) {
            throw new BizException("单次天窗 " + minutes + " 分钟，超过上限 " + WorksDict.MAX_WINDOW_MINUTES + " 分钟，需分段提报");
        }
        LocalDate windowDate = req.getWindowDate() == null ? LocalDate.now() : req.getWindowDate();
        for (WorkOrder exist : workOrderRepository.findAllBySectionIdOrderByWindowDateAsc(section.getId())) {
            if (windowDate.equals(exist.getWindowDate())) {
                throw new BizException("区段 " + section.getSectionNo() + " 在 " + windowDate + " 已排天窗 "
                        + exist.getOrderNo() + "，不能重复");
            }
        }
        Integer workerCount = req.getWorkerCount() == null ? 0 : req.getWorkerCount();
        if (workerCount <= 0) {
            throw new BizException("作业人数必须大于 0");
        }
        WorkOrder order = new WorkOrder();
        order.setOrderNo(orderNo);
        order.setSectionId(section.getId());
        order.setWorkItem(req.getWorkItem());
        order.setWindowDate(windowDate);
        order.setStartTime(start);
        order.setEndTime(end);
        order.setLeader(req.getLeader() == null ? "" : req.getLeader().trim());
        order.setWorkerCount(workerCount);
        order.setCooperateUnit(req.getCooperateUnit());
        order.setStatus("PLAN");
        return viewSupport.toView(workOrderRepository.save(order));
    }

    /** 状态推进：计划 → 已批复 → 施工中 → 已验收；已验收之后由封锁解除交接台关闭 */
    @Transactional
    public Views.WorkOrderView advance(Long id) {
        WorkOrder order = workOrderRepository.findById(id)
                .orElseThrow(() -> new BizException("作业单不存在：id=" + id));
        if ("CLOSED".equals(order.getStatus())) {
            throw new BizException("作业单 " + order.getOrderNo() + " 已销号，不能再推进");
        }
        if ("CHECKED".equals(order.getStatus())) {
            throw new BizException("「已验收 → 已销号」必须发起封锁解除交接、经复核通过后关闭，不能直接推进");
        }
        String next = WorksDict.nextWorkStatus(order.getStatus());
        if (next == null) {
            throw new BizException("作业单 " + order.getOrderNo() + " 已到终态，不能再推进");
        }
        if ("WORKING".equals(next)) {
            Section section = sectionRepository.findById(order.getSectionId()).orElse(null);
            if (section != null && "BLOCKED".equals(section.getStatus())) {
                throw new BizException("区段 " + section.getSectionNo() + " 已封锁施工，不能重复开工");
            }
        }
        order.setStatus(next);
        if ("WORKING".equals(next)) {
            Section section = sectionRepository.findById(order.getSectionId()).orElse(null);
            if (section != null) {
                section.setStatus("BLOCKED");
                section.setSpeedLimit(0);
                sectionRepository.save(section);
            }
        } else if ("CHECKED".equals(next)) {
            Section section = sectionRepository.findById(order.getSectionId()).orElse(null);
            if (section != null && "BLOCKED".equals(section.getStatus())) {
                section.setStatus("SPEED_LIMIT");
                section.setSpeedLimit(Math.min(WorksDict.DEFAULT_SPEED_LIMIT, section.getDesignSpeed()));
                sectionRepository.save(section);
            }
        }
        WorkOrder saved = workOrderRepository.save(order);
        // 作业状态 / 区段状态变了：引用该作业或该区段的待办交接申请一律失效
        blockReleaseService.invalidateStaleFor(saved.getId(), saved.getSectionId());
        return viewSupport.toView(saved);
    }
}
