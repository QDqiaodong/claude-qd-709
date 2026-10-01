package com.railway.works;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.railway.works.common.BizException;
import com.railway.works.domain.Defect;
import com.railway.works.domain.Inspection;
import com.railway.works.domain.Line;
import com.railway.works.domain.Section;
import com.railway.works.domain.WorkOrder;
import com.railway.works.dto.BlockReleaseDtos;
import com.railway.works.dto.Views;
import com.railway.works.repository.BlockReleaseLogRepository;
import com.railway.works.repository.BlockReleaseRepository;
import com.railway.works.repository.DefectRepository;
import com.railway.works.repository.InspectionRepository;
import com.railway.works.repository.LineRepository;
import com.railway.works.repository.SectionRepository;
import com.railway.works.repository.WorkOrderRepository;
import com.railway.works.service.BlockReleaseService;
import com.railway.works.service.DefectService;
import com.railway.works.service.WorkOrderService;
import com.railway.works.dto.DefectDtos;

/**
 * 封锁解除交接台端到端规则测试（H2 MySQL 兼容模式）：
 * 发起校验、退回/重提、三类失效检测、复核人约束、重级病害硬卡口、
 * 并发唯一结论、交接通过的跨对象原子更新。
 */
@SpringBootTest
class BlockReleaseIntegrationTests {

    @Autowired
    private BlockReleaseService releaseService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private DefectService defectService;
    @Autowired
    private LineRepository lineRepository;
    @Autowired
    private SectionRepository sectionRepository;
    @Autowired
    private WorkOrderRepository workOrderRepository;
    @Autowired
    private InspectionRepository inspectionRepository;
    @Autowired
    private DefectRepository defectRepository;
    @Autowired
    private BlockReleaseLogRepository logRepository;
    @Autowired
    private BlockReleaseRepository blockReleaseRepository;

    private Section heavySection;   // 有未销号重级病害的区段
    private Section cleanSection;   // 无病害区段
    private WorkOrder checkedHeavy; // heavySection 上的已验收作业
    private WorkOrder checkedClean; // cleanSection 上的已验收作业
    private WorkOrder planOrder;
    private Defect heavyDefect;

    @BeforeEach
    void setUp() {
        logRepository.deleteAll();
        blockReleaseRepository.deleteAll();
        defectRepository.deleteAll();
        inspectionRepository.deleteAll();
        workOrderRepository.deleteAll();
        sectionRepository.deleteAll();
        lineRepository.deleteAll();

        Line line = new Line();
        line.setName("测试京广线");
        line.setStartStation("甲");
        line.setEndStation("乙");
        line.setMileageKm(new BigDecimal("100.000"));
        line.setDirection("UP");
        lineRepository.save(line);

        heavySection = makeSection(line.getId(), "T-01", 160, "SPEED_LIMIT", 60);
        cleanSection = makeSection(line.getId(), "T-02", 200, "SPEED_LIMIT", 45);

        Inspection ins = new Inspection();
        ins.setInspectNo("INS-T-1");
        ins.setSectionId(heavySection.getId());
        ins.setInspector("检查员");
        ins.setInspectDate(LocalDate.now().minusDays(3));
        ins.setInspectType("STATIC");
        inspectionRepository.save(ins);

        heavyDefect = makeDefect(ins.getId(), heavySection.getId(), "HEAVY");
        makeDefect(ins.getId(), heavySection.getId(), "LIGHT");

        checkedHeavy = makeOrder("WO-T-H", heavySection.getId(), "王建国", "CHECKED");
        checkedClean = makeOrder("WO-T-C", cleanSection.getId(), "李长顺", "CHECKED");
        planOrder = makeOrder("WO-T-P", heavySection.getId(), "王建国", "PLAN");
    }

    private Section makeSection(Long lineId, String no, int designSpeed, String status, Integer speed) {
        Section s = new Section();
        s.setLineId(lineId);
        s.setSectionNo(no);
        s.setStartMile(new BigDecimal("10.000"));
        s.setEndMile(new BigDecimal("20.000"));
        s.setRailType("60KG");
        s.setBedType("BALLAST");
        s.setDesignSpeed(designSpeed);
        s.setStatus(status);
        s.setSpeedLimit(speed);
        return sectionRepository.save(s);
    }

    private WorkOrder makeOrder(String no, Long sectionId, String leader, String status) {
        WorkOrder o = new WorkOrder();
        o.setOrderNo(no);
        o.setSectionId(sectionId);
        o.setWorkItem("TAMP");
        o.setWindowDate(LocalDate.now());
        o.setStartTime(java.time.LocalTime.of(0, 30));
        o.setEndTime(java.time.LocalTime.of(3, 30));
        o.setLeader(leader);
        o.setWorkerCount(20);
        o.setStatus(status);
        return workOrderRepository.save(o);
    }

    private Defect makeDefect(Long inspectionId, Long sectionId, String severity) {
        Defect d = new Defect();
        d.setInspectionId(inspectionId);
        d.setSectionId(sectionId);
        d.setDefectType("SETTLEMENT");
        d.setSeverity(severity);
        d.setMileage(new BigDecimal("15.000"));
        d.setDescription(severity + "级测试病害 " + severity + System.nanoTime());
        d.setFoundDate(LocalDate.now().minusDays(2));
        d.setClosed(false);
        return defectRepository.save(d);
    }

    private BlockReleaseDtos.ReleaseSubmitReq submitReq(Long workOrderId, String leader, String suggestion) {
        BlockReleaseDtos.ReleaseSubmitReq req = new BlockReleaseDtos.ReleaseSubmitReq();
        req.setWorkOrderId(workOrderId);
        req.setSiteClear("清障完毕");
        req.setStaffWithdrawn("人员全部撤离");
        req.setEquipmentRemoved("机具全部撤出");
        req.setRetestResult("复测合格");
        req.setSuggestion(suggestion);
        req.setSuggestedSpeed("SPEED_LIMIT".equals(suggestion) ? 45 : null);
        req.setLimitReason("SPEED_LIMIT".equals(suggestion) ? "观察期限速" : null);
        req.setLeader(leader);
        return req;
    }

    private BlockReleaseDtos.ReleaseReviewReq reviewReq(String reviewer, String action,
                                                        String decision, Integer speed, String comment) {
        BlockReleaseDtos.ReleaseReviewReq req = new BlockReleaseDtos.ReleaseReviewReq();
        req.setReviewer(reviewer);
        req.setAction(action);
        req.setDecision(decision);
        req.setFinalSpeed(speed);
        req.setFinalReason("SPEED_LIMIT".equals(decision) ? "复核后仍需观察，限速" : null);
        req.setComment(comment);
        return req;
    }

    // ---------------- 发起校验 ----------------

    @Test
    void 非已验收作业不能发起() {
        assertThatThrownBy(() -> releaseService.submit(submitReq(planOrder.getId(), "王建国", "SPEED_LIMIT")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已验收");
    }

    @Test
    void 有未销号重级病害时不能建议恢复正常() {
        assertThatThrownBy(() -> releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "NORMAL")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重级病害");
    }

    @Test
    void 限速必须给值和原因且不高于设计时速() {
        BlockReleaseDtos.ReleaseSubmitReq req = submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT");
        req.setSuggestedSpeed(200);
        assertThatThrownBy(() -> releaseService.submit(req))
                .isInstanceOf(BizException.class).hasMessageContaining("设计时速");

        req.setSuggestedSpeed(45);
        req.setLimitReason(" ");
        assertThatThrownBy(() -> releaseService.submit(req))
                .isInstanceOf(BizException.class).hasMessageContaining("限速原因");
    }

    @Test
    void 同一作业单同时只能有一张未终结申请() {
        releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT"));
        assertThatThrownBy(() -> releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")))
                .isInstanceOf(BizException.class).hasMessageContaining("已有");
    }

    @Test
    void 正常发起后状态为待复核且记住快照() {
        Views.BlockReleaseView view = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT"));
        assertThat(view.getStatus()).isEqualTo("PENDING");
        assertThat(view.getCurrentHeavyOpen()).isTrue();
        assertThat(view.getSnapshotHeavyDefectIds()).containsExactly(heavyDefect.getId());
        assertThat(view.getChanges()).anyMatch(c -> "HEAVY_DEFECT".equals(c.getChangeType()) && !c.getChanged());
    }

    // ---------------- 退回 / 重提 ----------------

    @Test
    void 复核人不能与负责人相同且退回必须填意见() {
        Long id = releaseService.submit(submitReq(checkedClean.getId(), "李长顺", "NORMAL")).getId();
        assertThatThrownBy(() -> releaseService.review(id, reviewReq("李长顺", "APPROVE", "NORMAL", null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("同一人");

        assertThatThrownBy(() -> releaseService.review(id, reviewReq("赵复核", "REJECT", null, null, "  ")))
                .isInstanceOf(BizException.class).hasMessageContaining("整改意见");
    }

    @Test
    void 退回后可重提且只有已退回能重提() {
        Long id = releaseService.submit(submitReq(checkedClean.getId(), "李长顺", "NORMAL")).getId();
        releaseService.review(id, reviewReq("赵复核", "REJECT", null, null, "复测记录补齐"));
        assertThat(releaseService.detail(id).getStatus()).isEqualTo("REJECTED");

        Views.BlockReleaseView again = releaseService.resubmit(id, submitReq(checkedClean.getId(), "李长顺", "NORMAL"));
        assertThat(again.getStatus()).isEqualTo("PENDING");
        assertThat(again.getReviewer()).isNull();
        assertThat(logRepository.findAllByReleaseIdOrderByActedAtAscIdAsc(id))
                .extracting("action").containsExactly("SUBMIT", "REJECT", "RESUBMIT");

        Long otherId = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")).getId();
        assertThatThrownBy(() -> releaseService.resubmit(otherId, submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")))
                .isInstanceOf(BizException.class).hasMessageContaining("已退回");
    }

    // ---------------- 失效检测三类变化项 ----------------

    @Test
    void 重级病害销号后待复核申请自动失效并展示变化项() {
        Long id = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")).getId();
        DefectDtos.DefectCloseReq closeReq = new DefectDtos.DefectCloseReq();
        closeReq.setTreatment("起道捣固复检合格");
        defectService.close(heavyDefect.getId(), closeReq);

        Views.BlockReleaseView view = releaseService.detail(id);
        assertThat(view.getStatus()).isEqualTo("INVALID");
        assertThat(view.getInvalidReason()).contains("重级病害");
        assertThat(view.getChanges()).anyMatch(c -> "HEAVY_DEFECT".equals(c.getChangeType()) && c.getChanged());

        // 失效后不允许再复核，旧结论不可套用
        assertThatThrownBy(() -> releaseService.review(id, reviewReq("赵复核", "APPROVE", "NORMAL", null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已变化");
    }

    @Test
    void 区段状态变化后申请失效() {
        Long id = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")).getId();
        Section s = sectionRepository.findById(heavySection.getId()).orElseThrow();
        s.setStatus("BLOCKED");
        s.setSpeedLimit(0);
        sectionRepository.save(s);
        releaseService.invalidateStaleFor(null, heavySection.getId());

        Views.BlockReleaseView view = releaseService.detail(id);
        assertThat(view.getStatus()).isEqualTo("INVALID");
        assertThat(view.getChanges()).anyMatch(c -> "SECTION_STATUS".equals(c.getChangeType()) && c.getChanged());
    }

    @Test
    void 作业单状态变化后申请失效() {
        Long id = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")).getId();
        // 直接把作业改成施工中（模拟绕过前端的状态回退），触发失效
        WorkOrder o = workOrderRepository.findById(checkedHeavy.getId()).orElseThrow();
        o.setStatus("WORKING");
        workOrderRepository.save(o);
        releaseService.invalidateStaleFor(checkedHeavy.getId(), heavySection.getId());
        assertThat(releaseService.detail(id).getStatus()).isEqualTo("INVALID");
    }

    // ---------------- 已验收不能直接销号 ----------------

    @Test
    void 已验收到已销号不能绕过交接台() {
        assertThatThrownBy(() -> workOrderService.advance(checkedClean.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("封锁解除交接");
    }

    // ---------------- 列表筛选 ----------------

    @Test
    void 按线路区段状态筛选() {
        Long keep = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")).getId();
        releaseService.submit(submitReq(checkedClean.getId(), "李长顺", "NORMAL"));
        releaseService.review(keep, reviewReq("赵复核", "REJECT", null, null, "补复测签字"));

        List<Views.BlockReleaseView> all = releaseService.list(null, null, null);
        assertThat(all).hasSize(2);
        assertThat(releaseService.list(heavySection.getLineId(), null, null)).hasSize(2);
        assertThat(releaseService.list(null, heavySection.getId(), null)).hasSize(1)
                .first().extracting(Views.BlockReleaseView::getSectionId).isEqualTo(heavySection.getId());
        assertThat(releaseService.list(null, null, "REJECTED")).hasSize(1)
                .first().extracting(Views.BlockReleaseView::getId).isEqualTo(keep);
        assertThat(releaseService.list(null, cleanSection.getId(), "PENDING")).hasSize(1);
        assertThat(releaseService.list(null, cleanSection.getId(), "REJECTED")).isEmpty();
    }

    // ---------------- 复核通过：原子关闭作业 + 更新区段 ----------------

    @Test
    void 无重级病害复核恢复正常后原子销号并更新区段() {
        Long id = releaseService.submit(submitReq(checkedClean.getId(), "李长顺", "NORMAL")).getId();
        Views.BlockReleaseView done = releaseService.review(id, reviewReq("赵复核", "APPROVE", "NORMAL", null, "同意"));

        assertThat(done.getStatus()).isEqualTo("APPROVED");
        assertThat(done.getFinalDecision()).isEqualTo("NORMAL");
        WorkOrder order = workOrderRepository.findById(checkedClean.getId()).orElseThrow();
        Section section = sectionRepository.findById(cleanSection.getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo("CLOSED");
        assertThat(section.getStatus()).isEqualTo("NORMAL");
        assertThat(section.getSpeedLimit()).isEqualTo(200);
        assertThat(logRepository.findAllByReleaseIdOrderByActedAtAscIdAsc(id))
                .extracting("action").containsExactly("SUBMIT", "APPROVE");
    }

    @Test
    void 有重级病害复核不能恢复正常但可继续限速() {
        Long id = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT")).getId();
        assertThatThrownBy(() -> releaseService.review(id, reviewReq("赵复核", "APPROVE", "NORMAL", null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("重级病害");

        Views.BlockReleaseView done = releaseService.review(id, reviewReq("赵复核", "APPROVE", "SPEED_LIMIT", 45, null));
        assertThat(done.getStatus()).isEqualTo("APPROVED");
        assertThat(workOrderRepository.findById(checkedHeavy.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        Section section = sectionRepository.findById(heavySection.getId()).orElseThrow();
        assertThat(section.getStatus()).isEqualTo("SPEED_LIMIT");
        assertThat(section.getSpeedLimit()).isEqualTo(45);
    }

    @Test
    void 复核结论为继续封锁时段区封锁且限速归零() {
        Long id = releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "BLOCKED")).getId();
        releaseService.review(id, reviewReq("赵复核", "APPROVE", "BLOCKED", null, "继续封锁"));
        Section section = sectionRepository.findById(heavySection.getId()).orElseThrow();
        assertThat(section.getStatus()).isEqualTo("BLOCKED");
        assertThat(section.getSpeedLimit()).isZero();
    }

    @Test
    void 退回不关闭作业也不改区段() {
        Long id = releaseService.submit(submitReq(checkedClean.getId(), "李长顺", "NORMAL")).getId();
        releaseService.review(id, reviewReq("赵复核", "REJECT", null, null, "补齐复测签字"));
        assertThat(workOrderRepository.findById(checkedClean.getId()).orElseThrow().getStatus()).isEqualTo("CHECKED");
        assertThat(sectionRepository.findById(cleanSection.getId()).orElseThrow().getStatus()).isEqualTo("SPEED_LIMIT");
    }

    // ---------------- 并发唯一结论 ----------------

    @Test
    void 两名复核人并发处理只有一份结论生效() throws Exception {
        Long id = releaseService.submit(submitReq(checkedClean.getId(), "李长顺", "NORMAL")).getId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable r1 = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                releaseService.review(id, reviewReq("赵复核", "APPROVE", "NORMAL", null, null));
                success.incrementAndGet();
            } catch (BizException e) {
                rejected.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Runnable r2 = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                releaseService.review(id, reviewReq("钱复核", "APPROVE", "NORMAL", null, null));
                success.incrementAndGet();
            } catch (BizException e) {
                rejected.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Future<?> f1 = pool.submit(r1);
        Future<?> f2 = pool.submit(r2);
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        Views.BlockReleaseView view = releaseService.detail(id);
        assertThat(view.getStatus()).isEqualTo("APPROVED");
        long approveLogs = logRepository.findAllByReleaseIdOrderByActedAtAscIdAsc(id).stream()
                .filter(l -> "APPROVE".equals(l.getAction())).count();
        assertThat(approveLogs).isEqualTo(1);
        assertThat(workOrderRepository.findById(checkedClean.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void 两名负责人并发发起同一作业单只产生一张待复核() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable task = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                releaseService.submit(submitReq(checkedHeavy.getId(), "王建国", "SPEED_LIMIT"));
                success.incrementAndGet();
            } catch (BizException e) {
                rejected.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(releaseService.list(null, heavySection.getId(), "PENDING")).hasSize(1);
    }

    @Test
    void 已通过申请重复处理不会产生第二次交接() {
        Long id = releaseService.submit(submitReq(checkedClean.getId(), "李长顺", "NORMAL")).getId();
        releaseService.review(id, reviewReq("赵复核", "APPROVE", "NORMAL", null, null));
        assertThatThrownBy(() -> releaseService.review(id, reviewReq("钱复核", "APPROVE", "NORMAL", null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已变化");
        assertThat(logRepository.findAllByReleaseIdOrderByActedAtAscIdAsc(id).stream()
                .filter(l -> "APPROVE".equals(l.getAction())).count()).isEqualTo(1);
    }
}
