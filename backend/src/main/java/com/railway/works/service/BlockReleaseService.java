package com.railway.works.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.railway.works.common.BizException;
import com.railway.works.domain.BlockRelease;
import com.railway.works.domain.BlockReleaseLog;
import com.railway.works.domain.Defect;
import com.railway.works.domain.Section;
import com.railway.works.domain.WorkOrder;
import com.railway.works.domain.WorksDict;
import com.railway.works.dto.BlockReleaseDtos;
import com.railway.works.dto.Views;
import com.railway.works.repository.BlockReleaseLogRepository;
import com.railway.works.repository.BlockReleaseRepository;
import com.railway.works.repository.DefectRepository;
import com.railway.works.repository.SectionRepository;
import com.railway.works.repository.WorkOrderRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * 封锁解除交接台。
 *
 * <p>业务口径：
 * <ol>
 *   <li>只有「已验收 CHECKED」作业能发起；同作业单同时只允许一张未终结（待复核/已退回）的交接单。</li>
 *   <li>发起人逐项填写 现场清障 / 人员撤离 / 机具撤出 / 线路复测，并给出
 *       恢复正常 / 继续限速 / 继续封锁 建议；限速必须给出不高于设计时速的值和原因。</li>
 *   <li>发起时记住作业状态、区段状态和未销号重级病害清单；复核完成前只要其中任一项变化，
 *       旧申请在读取时自动标记「已失效」并保留变化项，不允许再复核。</li>
 *   <li>复核人必须不是施工负责人；逐项确认（通过）或退回整改；区段仍有未销号重级病害时
 *       绝不能给「恢复正常」结论，但没有重级病害也不自动放行，结论由复核人承担。</li>
 *   <li>{@code version} 乐观锁 + 复核行锁：两名复核人并发处理时只有一份结论生效，
 *       重复点击 / 网络重试不会产生第二次交接记录。</li>
 *   <li>通过时在同一事务、同一业务结果里关闭作业单并更新区段状态，任一失败整体回滚，
 *       不留「作业已销号但区段未更新」的半完成状态。</li>
 * </ol>
 */
@Service
public class BlockReleaseService {

    private final BlockReleaseRepository releaseRepository;
    private final BlockReleaseLogRepository logRepository;
    private final WorkOrderRepository workOrderRepository;
    private final SectionRepository sectionRepository;
    private final DefectRepository defectRepository;
    private final ViewSupport viewSupport;
    private final EntityManager entityManager;

    public BlockReleaseService(BlockReleaseRepository releaseRepository,
                               BlockReleaseLogRepository logRepository,
                               WorkOrderRepository workOrderRepository,
                               SectionRepository sectionRepository,
                               DefectRepository defectRepository,
                               ViewSupport viewSupport,
                               EntityManager entityManager) {
        this.releaseRepository = releaseRepository;
        this.logRepository = logRepository;
        this.workOrderRepository = workOrderRepository;
        this.sectionRepository = sectionRepository;
        this.defectRepository = defectRepository;
        this.viewSupport = viewSupport;
        this.entityManager = entityManager;
    }

    // ================= 列表 / 详情 =================

    @Transactional(readOnly = true)
    public List<Views.BlockReleaseView> list(Long lineId, Long sectionId, String status) {
        String statusCode = (status == null || status.isBlank()) ? null : status.trim();
        if (statusCode != null && !WorksDict.isValidReleaseStatus(statusCode)) {
            throw new BizException("交接状态只能是 待复核 / 已通过 / 已退回 / 已失效");
        }
        List<Views.BlockReleaseView> list = new ArrayList<>();
        for (BlockRelease release : releaseRepository.search(lineId, sectionId, statusCode)) {
            list.add(toView(release, false));
        }
        return list;
    }

    @Transactional
    public Views.BlockReleaseView detail(Long id) {
        BlockRelease release = getRelease(id);
        // 详情打开即触发失效检测，保证页面看到的就是最新结论可用性
        autoInvalidateIfStale(release);
        return toView(release, true);
    }

    // ================= 发起 / 重提 =================

    @Transactional
    public Views.BlockReleaseView submit(BlockReleaseDtos.ReleaseSubmitReq req) {
        // 锁住作业单行：并发发起同作业单的解除申请时串行化，杜绝两张待复核单同时产生
        WorkOrder lockedOrder = entityManager.find(WorkOrder.class, req.getWorkOrderId(), LockModeType.PESSIMISTIC_WRITE);
        if (lockedOrder == null) {
            throw new BizException("养修作业单不存在：id=" + req.getWorkOrderId());
        }
        WorkOrder order = lockedOrder;
        if (!"CHECKED".equals(order.getStatus())) {
            throw new BizException("只有「已验收」的作业单才能发起封锁解除，当前状态："
                    + WorksDict.workStatusName(order.getStatus()));
        }
        if (releaseRepository.countOpenByWorkOrderId(order.getId()) > 0) {
            throw new BizException("作业单 " + order.getOrderNo() + " 已有待复核或已退回的交接申请，请先处理后再发起");
        }
        Section section = sectionRepository.findById(order.getSectionId())
                .orElseThrow(() -> new BizException("作业单所属区段不存在"));

        String leader = req.getLeader().trim();
        if (leader.isBlank()) {
            throw new BizException("施工负责人不能为空");
        }
        validateSuggestion(req.getSuggestion(), req.getSuggestedSpeed(), req.getLimitReason(),
                section.getDesignSpeed(), heavyDefects(section.getId()));

        List<Defect> heavy = heavyDefects(section.getId());
        LocalDateTime now = LocalDateTime.now();
        BlockRelease release = new BlockRelease();
        release.setReleaseNo(nextReleaseNo());
        release.setWorkOrderId(order.getId());
        release.setSectionId(section.getId());
        release.setSiteClear(trim(req.getSiteClear()));
        release.setStaffWithdrawn(trim(req.getStaffWithdrawn()));
        release.setEquipmentRemoved(trim(req.getEquipmentRemoved()));
        release.setRetestResult(trim(req.getRetestResult()));
        release.setSuggestion(req.getSuggestion().trim());
        release.setSuggestedSpeed("SPEED_LIMIT".equals(req.getSuggestion()) ? req.getSuggestedSpeed() : null);
        release.setLimitReason("SPEED_LIMIT".equals(req.getSuggestion()) ? trim(req.getLimitReason()) : null);
        release.setLeader(leader);
        release.setStatus("PENDING");
        release.setSnapshotWorkStatus(order.getStatus());
        release.setSnapshotSectionStatus(section.getStatus());
        release.setSnapshotSectionSpeed(section.getSpeedLimit());
        release.setSnapshotHeavyDefectIds(heavy.stream().map(d -> String.valueOf(d.getId())).collect(Collectors.joining(",")));
        release.setSnapshotHeavyDefectText(heavyText(heavy));
        release.setSubmittedAt(now);
        BlockRelease saved = releaseRepository.save(release);
        writeLog(saved.getId(), "SUBMIT", leader, now, "发起封锁解除申请，建议："
                + WorksDict.releaseDecisionName(saved.getSuggestion()), "PENDING");
        return toView(saved, true);
    }

    /** 已退回申请由施工负责人按最新现场重新提交（同一条流程保留全部轨迹） */
    @Transactional
    public Views.BlockReleaseView resubmit(Long id, BlockReleaseDtos.ReleaseSubmitReq req) {
        BlockRelease release = getRelease(id);
        WorkOrder order = workOrderRepository.findById(release.getWorkOrderId())
                .orElseThrow(() -> new BizException("养修作业单已不存在"));
        if (!"REJECTED".equals(release.getStatus())) {
            throw new BizException("只有「已退回」的交接申请才能重新提交，当前状态："
                    + WorksDict.releaseStatusName(release.getStatus()));
        }
        if (!"CHECKED".equals(order.getStatus())) {
            throw new BizException("作业单当前已不是「已验收」（"
                    + WorksDict.workStatusName(order.getStatus()) + "），不能按原流程重提，请基于最新现场重新发起");
        }
        Section section = sectionRepository.findById(release.getSectionId())
                .orElseThrow(() -> new BizException("区段不存在"));

        String leader = req.getLeader().trim();
        if (leader.isBlank()) {
            throw new BizException("施工负责人不能为空");
        }
        validateSuggestion(req.getSuggestion(), req.getSuggestedSpeed(), req.getLimitReason(),
                section.getDesignSpeed(), heavyDefects(section.getId()));

        List<Defect> heavy = heavyDefects(section.getId());
        LocalDateTime now = LocalDateTime.now();
        release.setSiteClear(trim(req.getSiteClear()));
        release.setStaffWithdrawn(trim(req.getStaffWithdrawn()));
        release.setEquipmentRemoved(trim(req.getEquipmentRemoved()));
        release.setRetestResult(trim(req.getRetestResult()));
        release.setSuggestion(req.getSuggestion().trim());
        release.setSuggestedSpeed("SPEED_LIMIT".equals(req.getSuggestion()) ? req.getSuggestedSpeed() : null);
        release.setLimitReason("SPEED_LIMIT".equals(req.getSuggestion()) ? trim(req.getLimitReason()) : null);
        release.setLeader(leader);
        release.setReviewer(null);
        release.setStatus("PENDING");
        release.setFinalDecision(null);
        release.setFinalSpeed(null);
        release.setFinalReason(null);
        release.setReviewedAt(null);
        release.setInvalidatedAt(null);
        release.setInvalidReason(null);
        release.setSnapshotWorkStatus(order.getStatus());
        release.setSnapshotSectionStatus(section.getStatus());
        release.setSnapshotSectionSpeed(section.getSpeedLimit());
        release.setSnapshotHeavyDefectIds(heavy.stream().map(d -> String.valueOf(d.getId())).collect(Collectors.joining(",")));
        release.setSnapshotHeavyDefectText(heavyText(heavy));
        release.setSubmittedAt(now);
        BlockRelease saved = releaseRepository.save(release);
        writeLog(saved.getId(), "RESUBMIT", leader, now, "按最新现场重新提交，建议："
                + WorksDict.releaseDecisionName(saved.getSuggestion()), "PENDING");
        return toView(saved, true);
    }

    // ================= 复核 =================

    @Transactional
    public Views.BlockReleaseView review(Long id, BlockReleaseDtos.ReleaseReviewReq req) {
        String reviewer = req.getReviewer() == null ? "" : req.getReviewer().trim();
        if (reviewer.isEmpty()) {
            throw new BizException("复核人不能为空");
        }
        String action = req.getAction() == null ? "" : req.getAction().trim();
        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) {
            throw new BizException("复核操作只能是通过 APPROVE 或退回 REJECT");
        }

        // 行锁串行化同一条申请的并发复核；乐观锁再兜住重复提交 / 网络重试
        BlockRelease release = entityManager.find(BlockRelease.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (release == null) {
            throw new BizException("交接申请不存在：id=" + id);
        }
        if (!"PENDING".equals(release.getStatus())) {
            throw new BizException("申请当前为「" + WorksDict.releaseStatusName(release.getStatus())
                    + "」，申请已变化，请勿重复处理，请刷新后查看最新状态");
        }
        // 锁住作业单与区段行：复核结论落账期间，作业推进 / 区段状态手工变更必须排队，
        // 等锁后看到的就是最新现场，避免复核写覆盖。
        WorkOrder lockedOrder = entityManager.find(WorkOrder.class, release.getWorkOrderId(), LockModeType.PESSIMISTIC_WRITE);
        Section lockedSection = entityManager.find(Section.class, release.getSectionId(), LockModeType.PESSIMISTIC_WRITE);
        // 复核提交瞬间再做一次现场比对：现场已变就不能套用旧结论
        List<Views.ReleaseChangeView> changes = detectChanges(release, lockedOrder, lockedSection);
        List<Views.ReleaseChangeView> hit = changedItems(changes);
        if (!hit.isEmpty()) {
            String reason = joinChanges(hit);
            invalidate(release, reason, LocalDateTime.now());
            throw new BizException("现场已变化，该申请已自动失效，必须基于最新现场重新提交；变化项：" + reason);
        }
        if (reviewer.equals(release.getLeader())) {
            throw new BizException("复核人不能与施工负责人是同一人（" + release.getLeader() + "），请换人复核");
        }

        LocalDateTime now = LocalDateTime.now();
        if ("REJECT".equals(action)) {
            String comment = req.getComment() == null ? "" : req.getComment().trim();
            if (comment.isEmpty()) {
                throw new BizException("退回整改必须填写整改意见");
            }
            release.setStatus("REJECTED");
            release.setReviewer(reviewer);
            release.setReviewedAt(now);
            BlockRelease saved = safeSave(release);
            writeLog(saved.getId(), "REJECT", reviewer, now, comment, "REJECTED");
            return toView(saved, true);
        }

        // APPROVE：逐项确认后的最终结论
        String decision = req.getDecision() == null ? "" : req.getDecision().trim();
        if (!WorksDict.isValidReleaseDecision(decision)) {
            throw new BizException("请给出最终复核结论：恢复正常 / 继续限速 / 继续封锁");
        }
        Section section = lockedSection;
        List<Defect> heavy = heavyDefects(section.getId());
        validateDecision(decision, req.getFinalSpeed(), req.getFinalReason(), section.getDesignSpeed(), heavy);

        release.setReviewer(reviewer);
        release.setReviewedAt(now);
        release.setStatus("APPROVED");
        release.setFinalDecision(decision);
        release.setFinalSpeed("SPEED_LIMIT".equals(decision) ? req.getFinalSpeed() : null);
        release.setFinalReason("SPEED_LIMIT".equals(decision) ? trim(req.getFinalReason()) : trim(req.getComment()));

        // 同一次业务结果、同一事务内：交接通过 → 关闭养修作业 → 更新区段状态。
        // 任一步失败整个事务回滚，绝不留下半完成状态。
        WorkOrder order = lockedOrder;
        if (!"CHECKED".equals(order.getStatus())) {
            throw new BizException("作业单状态已不是「已验收」，交接结论与现场不一致，本次通过已撤销");
        }
        order.setStatus("CLOSED");
        applySectionDecision(section, decision, req.getFinalSpeed());
        BlockRelease saved = safeSave(release);
        workOrderRepository.save(order);
        sectionRepository.save(section);
        String remark = "复核通过，结论：" + WorksDict.releaseDecisionName(decision)
                + ("SPEED_LIMIT".equals(decision) ? " " + req.getFinalSpeed() + "km/h" : "")
                + (req.getComment() == null || req.getComment().isBlank() ? "" : "；意见：" + req.getComment().trim());
        writeLog(saved.getId(), "APPROVE", reviewer, now, remark, "APPROVED");
        return toView(saved, true);
    }

    // ================= 失效检测 =================

    /**
     * 复核完成前，作业状态、区段状态或快照重级病害的销号情况发生变化时，
     * 把待复核 / 已退回的旧申请标记为已失效（幂等）。
     * 在申请被读取（详情/列表懒标记）以及外部对象变更后主动调用。
     */
    @Transactional
    public int invalidateStaleFor(Long workOrderId, Long sectionId) {
        int count = 0;
        List<BlockRelease> candidates = new ArrayList<>();
        if (workOrderId != null) {
            candidates.addAll(releaseRepository.findAllByWorkOrderIdOrderBySubmittedAtDescIdDesc(workOrderId));
        }
        if (sectionId != null) {
            for (BlockRelease release : releaseRepository.search(null, sectionId, null)) {
                if (candidates.stream().noneMatch(r -> r.getId().equals(release.getId()))) {
                    candidates.add(release);
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (BlockRelease release : candidates) {
            if (!"PENDING".equals(release.getStatus()) && !"REJECTED".equals(release.getStatus())) {
                continue;
            }
            List<Views.ReleaseChangeView> hit = changedItems(detectChanges(release));
            if (!hit.isEmpty()) {
                invalidate(release, joinChanges(hit), now);
                count++;
            }
        }
        return count;
    }

    private void autoInvalidateIfStale(BlockRelease release) {
        if (!"PENDING".equals(release.getStatus()) && !"REJECTED".equals(release.getStatus())) {
            return;
        }
        List<Views.ReleaseChangeView> hit = changedItems(detectChanges(release));
        if (!hit.isEmpty()) {
            invalidate(release, joinChanges(hit), LocalDateTime.now());
        }
    }

    private void invalidate(BlockRelease release, String reason, LocalDateTime now) {
        release.setStatus("INVALID");
        release.setInvalidatedAt(now);
        release.setInvalidReason(reason);
        BlockRelease saved = safeSave(release);
        writeLog(saved.getId(), "INVALIDATE", "系统", now, "复核失效：" + reason, "INVALID");
    }

    // ================= 现场比对 =================

    /** 逐项比对 作业状态 / 区段状态（含限速值）/ 重级病害销号情况，页面据此展示变化项 */
    private List<Views.ReleaseChangeView> detectChanges(BlockRelease release) {
        WorkOrder order = workOrderRepository.findById(release.getWorkOrderId()).orElse(null);
        Section section = sectionRepository.findById(release.getSectionId()).orElse(null);
        return detectChanges(release, order, section);
    }

    private List<Views.ReleaseChangeView> detectChanges(BlockRelease release, WorkOrder order, Section section) {
        List<Views.ReleaseChangeView> list = new ArrayList<>();
        String currentWork = order == null ? "作业单已删除" : WorksDict.workStatusName(order.getStatus());
        list.add(change("WORK_STATUS", "作业单状态",
                WorksDict.workStatusName(release.getSnapshotWorkStatus()), currentWork,
                order == null || !order.getStatus().equals(release.getSnapshotWorkStatus())));

        String currentSection = section == null
                ? "区段已删除"
                : WorksDict.sectionStatusName(section.getStatus())
                        + ("SPEED_LIMIT".equals(section.getStatus()) && section.getSpeedLimit() != null
                                ? " " + section.getSpeedLimit() + "km/h" : "");
        String snapshotSection = WorksDict.sectionStatusName(release.getSnapshotSectionStatus())
                + ("SPEED_LIMIT".equals(release.getSnapshotSectionStatus()) && release.getSnapshotSectionSpeed() != null
                        ? " " + release.getSnapshotSectionSpeed() + "km/h" : "");
        boolean sectionChanged = section == null
                || !section.getStatus().equals(release.getSnapshotSectionStatus())
                || !java.util.Objects.equals(section.getSpeedLimit(), release.getSnapshotSectionSpeed());
        list.add(change("SECTION_STATUS", "区段状态", snapshotSection, currentSection, sectionChanged));

        Set<Long> snapshotIds = parseIds(release.getSnapshotHeavyDefectIds());
        List<Defect> heavyNow = section == null ? List.of() : heavyDefects(section.getId());
        Set<Long> stillOpenIds = heavyNow.stream().map(Defect::getId).collect(Collectors.toSet());
        List<String> removed = new ArrayList<>();
        List<String> appeared = new ArrayList<>();
        for (Long defectId : snapshotIds) {
            if (!stillOpenIds.contains(defectId)) {
                removed.add(defectRepository.findById(defectId)
                        .map(d -> WorksDict.mileageText(d.getMileage()) + " " + d.getDescription())
                        .orElseGet(() -> "病害#" + defectId));
            }
        }
        for (Defect defect : heavyNow) {
            if (!snapshotIds.contains(defect.getId())) {
                appeared.add(WorksDict.mileageText(defect.getMileage()) + " " + defect.getDescription());
            }
        }
        String snapshotDefectText = release.getSnapshotHeavyDefectText() == null
                || release.getSnapshotHeavyDefectText().isBlank() ? "无未销号重级病害" : release.getSnapshotHeavyDefectText();
        String currentDefectText = heavyText(heavyNow);
        boolean defectChanged = !removed.isEmpty() || !appeared.isEmpty();
        Views.ReleaseChangeView defectChange = change("HEAVY_DEFECT", "未销号重级病害",
                snapshotDefectText, currentDefectText, defectChanged);
        if (defectChanged) {
            List<String> detail = new ArrayList<>();
            if (!removed.isEmpty()) {
                detail.add("已销号/消失：" + String.join("；", removed));
            }
            if (!appeared.isEmpty()) {
                detail.add("新发现/升级：" + String.join("；", appeared));
            }
            defectChange.setSnapshotText(snapshotDefectText);
            defectChange.setCurrentText(currentDefectText + "（" + String.join("；", detail) + "）");
        }
        list.add(defectChange);
        return list;
    }

    private List<Views.ReleaseChangeView> changedItems(List<Views.ReleaseChangeView> all) {
        List<Views.ReleaseChangeView> hit = new ArrayList<>();
        for (Views.ReleaseChangeView change : all) {
            if (Boolean.TRUE.equals(change.getChanged())) {
                hit.add(change);
            }
        }
        return hit;
    }

    private String joinChanges(List<Views.ReleaseChangeView> hit) {
        return hit.stream()
                .map(c -> c.getChangeTypeName() + "：" + c.getSnapshotText() + " → " + c.getCurrentText())
                .collect(Collectors.joining("；"));
    }

    private Views.ReleaseChangeView change(String type, String typeName, String snapshot, String current, boolean changed) {
        Views.ReleaseChangeView view = new Views.ReleaseChangeView();
        view.setChangeType(type);
        view.setChangeTypeName(typeName);
        view.setSnapshotText(snapshot);
        view.setCurrentText(current);
        view.setChanged(changed);
        return view;
    }

    // ================= 规则校验 =================

    private void validateSuggestion(String suggestion, Integer speed, String reason,
                                    Integer designSpeed, List<Defect> heavy) {
        if (!WorksDict.isValidReleaseDecision(suggestion)) {
            throw new BizException("建议只能是 恢复正常 / 继续限速 / 继续封锁");
        }
        if ("SPEED_LIMIT".equals(suggestion)) {
            validateSpeed(speed, reason, designSpeed);
        }
        if ("NORMAL".equals(suggestion) && !heavy.isEmpty()) {
            throw new BizException("区段仍有 " + heavy.size() + " 处未销号重级病害，绝不能恢复正常，只能建议继续限速或继续封锁");
        }
    }

    private void validateDecision(String decision, Integer speed, String reason,
                                  Integer designSpeed, List<Defect> heavy) {
        if ("SPEED_LIMIT".equals(decision)) {
            validateSpeed(speed, reason, designSpeed);
        }
        if ("NORMAL".equals(decision) && !heavy.isEmpty()) {
            throw new BizException("区段仍有 " + heavy.size()
                    + " 处未销号重级病害，复核结论绝不能为恢复正常，只能继续限速或继续封锁");
        }
        // 注意：没有重级病害也不自动放行——是否恢复正常由复核人逐项确认后自行承担结论。
    }

    private void validateSpeed(Integer speed, String reason, Integer designSpeed) {
        if (speed == null || speed <= 0) {
            throw new BizException("选择继续限速必须给出限速值（km/h）");
        }
        if (designSpeed != null && speed > designSpeed) {
            throw new BizException("限速值 " + speed + "km/h 高于设计时速 " + designSpeed + "km/h，不允许");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BizException("选择继续限速必须填写限速原因");
        }
    }

    /** 按复核结论更新区段：恢复正常 / 限速（值+原因写入备注）/ 继续封锁 */
    private void applySectionDecision(Section section, String decision, Integer speed) {
        if ("NORMAL".equals(decision)) {
            section.setStatus("NORMAL");
            section.setSpeedLimit(section.getDesignSpeed());
        } else if ("SPEED_LIMIT".equals(decision)) {
            section.setStatus("SPEED_LIMIT");
            section.setSpeedLimit(speed);
        } else {
            section.setStatus("BLOCKED");
            section.setSpeedLimit(0);
        }
    }

    // ================= 装配 =================

    private Views.BlockReleaseView toView(BlockRelease release, boolean withLogs) {
        Views.BlockReleaseView view = new Views.BlockReleaseView();
        view.setId(release.getId());
        view.setReleaseNo(release.getReleaseNo());
        view.setWorkOrderId(release.getWorkOrderId());
        workOrderRepository.findById(release.getWorkOrderId()).ifPresent(order -> {
            view.setOrderNo(order.getOrderNo());
            view.setWorkItem(order.getWorkItem());
            view.setWorkItemName(WorksDict.workItemName(order.getWorkItem()));
        });
        view.setSectionId(release.getSectionId());
        Section section = sectionRepository.findById(release.getSectionId()).orElse(null);
        if (section != null) {
            view.setSectionNo(section.getSectionNo());
            view.setLineId(section.getLineId());
            view.setLineName(viewSupport.lineName(section.getLineId()));
            view.setCurrentDesignSpeed(section.getDesignSpeed());
        }
        view.setSiteClear(release.getSiteClear());
        view.setStaffWithdrawn(release.getStaffWithdrawn());
        view.setEquipmentRemoved(release.getEquipmentRemoved());
        view.setRetestResult(release.getRetestResult());
        view.setSuggestion(release.getSuggestion());
        view.setSuggestionName(WorksDict.releaseDecisionName(release.getSuggestion()));
        view.setSuggestedSpeed(release.getSuggestedSpeed());
        view.setLimitReason(release.getLimitReason());
        view.setLeader(release.getLeader());
        view.setReviewer(release.getReviewer());
        view.setStatus(release.getStatus());
        view.setStatusName(WorksDict.releaseStatusName(release.getStatus()));
        view.setSnapshotWorkStatus(release.getSnapshotWorkStatus());
        view.setSnapshotWorkStatusName(WorksDict.workStatusName(release.getSnapshotWorkStatus()));
        view.setSnapshotSectionStatus(release.getSnapshotSectionStatus());
        view.setSnapshotSectionStatusName(WorksDict.sectionStatusName(release.getSnapshotSectionStatus()));
        view.setSnapshotSectionSpeed(release.getSnapshotSectionSpeed());
        view.setSnapshotHeavyDefectIds(new ArrayList<>(parseIds(release.getSnapshotHeavyDefectIds())));
        view.setSnapshotHeavyDefectText(
                release.getSnapshotHeavyDefectText() == null || release.getSnapshotHeavyDefectText().isBlank()
                        ? "无未销号重级病害" : release.getSnapshotHeavyDefectText());
        view.setFinalDecision(release.getFinalDecision());
        view.setFinalDecisionName(WorksDict.releaseDecisionName(release.getFinalDecision()));
        view.setFinalSpeed(release.getFinalSpeed());
        view.setFinalReason(release.getFinalReason());
        view.setSubmittedAt(release.getSubmittedAt());
        view.setReviewedAt(release.getReviewedAt());
        view.setInvalidatedAt(release.getInvalidatedAt());
        view.setInvalidReason(release.getInvalidReason());
        view.setVersion(release.getVersion());

        List<Views.ReleaseChangeView> changes = detectChanges(release);
        view.setChanges(changes);
        boolean heavyNow = changes.stream()
                .anyMatch(c -> "HEAVY_DEFECT".equals(c.getChangeType())
                        && !"无未销号重级病害".equals(c.getCurrentText()));
        view.setCurrentHeavyOpen(heavyNow);

        if (withLogs) {
            List<Views.ReleaseLogView> logs = new ArrayList<>();
            for (BlockReleaseLog log : logRepository.findAllByReleaseIdOrderByActedAtAscIdAsc(release.getId())) {
                Views.ReleaseLogView logView = new Views.ReleaseLogView();
                logView.setId(log.getId());
                logView.setAction(log.getAction());
                logView.setActionName(WorksDict.releaseActionName(log.getAction()));
                logView.setOperator(log.getOperator());
                logView.setActedAt(log.getActedAt());
                logView.setRemark(log.getRemark());
                logView.setResultStatus(log.getResultStatus());
                logView.setResultStatusName(WorksDict.releaseStatusName(log.getResultStatus()));
                logs.add(logView);
            }
            view.setLogs(logs);
        }
        return view;
    }

    // ================= 基础设施 =================

    private List<Defect> heavyDefects(Long sectionId) {
        return defectRepository.findAllBySectionIdAndSeverityAndClosedFalseOrderByMileageAsc(sectionId, "HEAVY");
    }

    private String heavyText(List<Defect> heavy) {
        if (heavy.isEmpty()) {
            return "无未销号重级病害";
        }
        return heavy.stream()
                .map(d -> WorksDict.mileageText(d.getMileage()) + " " + d.getDescription())
                .collect(Collectors.joining("；"));
    }

    private Set<Long> parseIds(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toSet());
    }

    private String trim(String text) {
        return text == null ? "" : text.trim();
    }

    private BlockRelease getRelease(Long id) {
        return releaseRepository.findById(id)
                .orElseThrow(() -> new BizException("交接申请不存在：id=" + id));
    }

    /** 乐观锁保存：并发下只有一份结论能落库 */
    private BlockRelease safeSave(BlockRelease release) {
        try {
            return releaseRepository.saveAndFlush(release);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BizException("申请已被其他人先处理，重复操作未生效，请刷新查看最新交接状态");
        }
    }

    private void writeLog(Long releaseId, String action, String operator, LocalDateTime at,
                          String remark, String resultStatus) {
        BlockReleaseLog log = new BlockReleaseLog();
        log.setReleaseId(releaseId);
        log.setAction(action);
        log.setOperator(operator);
        log.setActedAt(at);
        log.setRemark(remark);
        log.setResultStatus(resultStatus);
        logRepository.save(log);
    }

    /** 交接单号：BR-年-四位序号，按现有最大序号递增 */
    private synchronized String nextReleaseNo() {
        long max = releaseRepository.findAllByOrderBySubmittedAtDescIdDesc().stream()
                .map(BlockRelease::getReleaseNo)
                .filter(no -> no != null && no.startsWith("BR-"))
                .mapToLong(no -> {
                    try {
                        return Long.parseLong(no.substring(no.lastIndexOf('-') + 1));
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .max().orElse(0L);
        return String.format("BR-%d-%04d", java.time.LocalDate.now().getYear(), max + 1);
    }
}
