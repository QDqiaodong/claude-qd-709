-- ============================================================================
-- 铁路工务工区（线路养修）管理系统 · 建表 + 演示数据
-- 库名 railway_works，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 设计口径：
--   [T] 设备台账走 JPA 的 InheritanceType.TABLE_PER_CLASS：
--       rail / switch / sleeper / bridge / tunnel 五张表各自包含基类 Device 的全部字段，
--       主键不能用自增，统一由序列表 device_id_gen（TABLE 策略）发号，保证五表 id 全局唯一。
--   [S1] 线路名称唯一        → 京广上行 已存在
--   [S2] 区段编号唯一        → GX-01 已存在
--   [S3] 区段里程不得超出线路运营里程 → service 校验
--   [S4] 限速值不得高于设计时速 → GX-02 设计 160 / 限速 45
--   [D1] 设备编号五表全局唯一 → DeviceRepository.existsByDeviceNo 校验
--   [D2] 设备里程必须落在所属区段内 → service 校验
--   [D3] 定检周期 90 天，超期页面标黄 → RAIL-GX-05-01 上次检查 2026-05-20
--   [D4] 多态查询            → /api/devices 不传 type 时查基类 Device
--   [F1] 病害里程必须落在区段内 → service 校验
--   [F2] 已销号病害不可重复销号 → id=4 / id=9 已销号
--   [F3] 重级病害 7 天整治时限，逾期整行标红 → id=2（HEAVY，发现 2026-09-02）
--   [W1] 单次天窗不超过 300 分钟 → service 校验
--   [W2] 同区段同天窗日不可重复排程 → WO-2026-0001 占 GX-01 的 2026-09-26
--   [W3] 状态只能逐级推进       → 计划 → 已批复 → 施工中 → 已验收；
--                                「已验收 → 已销号」必须走封锁解除交接台（block_release）
--   [W4] 已销号作业单不可再推进 → WO-2026-0006 = CLOSED（升级前历史销号，不补造交接单）
--   [R1] 交接单四态             → 待复核 PENDING / 已通过 APPROVED / 已退回 REJECTED / 已失效 INVALID
--   [R2] 有未销号重级病害不能恢复正常，只能继续限速（限速值≤设计时速且填原因）或继续封锁
--   [R3] 发起即固化作业状态/区段状态/重级病害清单快照，任一项变化 → 旧单 INVALID
--   [R4] version 乐观锁         → 并发复核只有一份结论、一次销号与区段更新
--   [R5] 交接通过同事务原子完成  → 作业 CLOSED + 区段按结论 NORMAL/SPEED_LIMIT/BLOCKED
--   [M1] 库存低于安全库存预警   → 弹条扣件 / 液压起拨道机 等
--   [M2] 出库数量不得大于库存   → service 校验
--   [M3] 报废机具不再排检修     → EQ-0009 = SCRAP
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS railway_works DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE railway_works;

DROP TABLE IF EXISTS block_release_log;
DROP TABLE IF EXISTS block_release;
DROP TABLE IF EXISTS equipment;
DROP TABLE IF EXISTS material;
DROP TABLE IF EXISTS work_order;
DROP TABLE IF EXISTS defect;
DROP TABLE IF EXISTS inspection;
DROP TABLE IF EXISTS tunnel;
DROP TABLE IF EXISTS bridge;
DROP TABLE IF EXISTS sleeper;
DROP TABLE IF EXISTS `switch`;
DROP TABLE IF EXISTS rail;
DROP TABLE IF EXISTS device_id_gen;
DROP TABLE IF EXISTS section;
DROP TABLE IF EXISTS line;

-- ---------------- 模块一：线路与区段 ----------------
CREATE TABLE line (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(64)  NOT NULL COMMENT '线路名称，如 京广上行',
    start_station VARCHAR(64)  NOT NULL COMMENT '起点站',
    end_station   VARCHAR(64)  NOT NULL COMMENT '终点站',
    mileage_km    DECIMAL(10,3) NOT NULL COMMENT '运营里程（公里）',
    direction     VARCHAR(16)  NOT NULL COMMENT 'UP 上行 / DOWN 下行',
    bureau        VARCHAR(64)  COMMENT '所属铁路局',
    PRIMARY KEY (id),
    UNIQUE KEY uk_line_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE section (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    line_id      BIGINT        NOT NULL COMMENT '所属线路',
    section_no   VARCHAR(32)   NOT NULL COMMENT '区段编号',
    start_mile   DECIMAL(10,3) NOT NULL COMMENT '里程起点（公里）',
    end_mile     DECIMAL(10,3) NOT NULL COMMENT '里程终点（公里）',
    rail_type    VARCHAR(16)   NOT NULL COMMENT '轨型 60KG / 75KG',
    bed_type     VARCHAR(16)   NOT NULL COMMENT '道床 BALLAST 有砟 / SLAB 无砟',
    design_speed INT           NOT NULL COMMENT '设计时速',
    status       VARCHAR(16)   NOT NULL COMMENT 'NORMAL 正常 / SPEED_LIMIT 限速 / BLOCKED 封锁施工',
    speed_limit  INT           COMMENT '当前限速值',
    remark       VARCHAR(255),
    PRIMARY KEY (id),
    UNIQUE KEY uk_section_no (section_no),
    KEY idx_section_line (line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 模块二：设备台账（TABLE_PER_CLASS，五张完整表） ----------------
-- TABLE 策略发号：五张设备表共用一张序列表，保证多态查询时 id 不冲突
CREATE TABLE device_id_gen (
    gen_key   VARCHAR(64) NOT NULL,
    gen_value BIGINT      NOT NULL,
    PRIMARY KEY (gen_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rail (
    id              BIGINT        NOT NULL,
    device_no       VARCHAR(32)   NOT NULL,
    section_id      BIGINT        NOT NULL,
    mileage         DECIMAL(10,3) NOT NULL,
    install_date    DATE,
    last_check_date DATE,
    status          VARCHAR(16)   NOT NULL COMMENT 'GOOD 良好 / WATCH 关注 / FAULT 失格',
    remark          VARCHAR(255),
    rail_type       VARCHAR(16)   NOT NULL,
    rail_length     DECIMAL(8,2)  NOT NULL COMMENT '单根长度（米）',
    lock_temp       DECIMAL(5,1)  NOT NULL COMMENT '锁定轨温（℃）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rail_no (device_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `switch` (
    id              BIGINT        NOT NULL,
    device_no       VARCHAR(32)   NOT NULL,
    section_id      BIGINT        NOT NULL,
    mileage         DECIMAL(10,3) NOT NULL,
    install_date    DATE,
    last_check_date DATE,
    status          VARCHAR(16)   NOT NULL,
    remark          VARCHAR(255),
    switch_model    VARCHAR(32)   NOT NULL COMMENT '道岔型号，如 12号',
    direction       VARCHAR(16)   NOT NULL COMMENT 'LEFT 左开 / RIGHT 右开',
    machine_model   VARCHAR(32)   NOT NULL COMMENT '转辙机型',
    PRIMARY KEY (id),
    UNIQUE KEY uk_switch_no (device_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sleeper (
    id              BIGINT        NOT NULL,
    device_no       VARCHAR(32)   NOT NULL,
    section_id      BIGINT        NOT NULL,
    mileage         DECIMAL(10,3) NOT NULL,
    install_date    DATE,
    last_check_date DATE,
    status          VARCHAR(16)   NOT NULL,
    remark          VARCHAR(255),
    sleeper_type    VARCHAR(16)   NOT NULL COMMENT 'CONCRETE 混凝土 / WOOD 木枕',
    sleeper_count   INT           NOT NULL COMMENT '铺设根数',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sleeper_no (device_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bridge (
    id              BIGINT        NOT NULL,
    device_no       VARCHAR(32)   NOT NULL,
    section_id      BIGINT        NOT NULL,
    mileage         DECIMAL(10,3) NOT NULL,
    install_date    DATE,
    last_check_date DATE,
    status          VARCHAR(16)   NOT NULL,
    remark          VARCHAR(255),
    bridge_length   DECIMAL(8,2)  NOT NULL COMMENT '桥长（米）',
    span_layout     VARCHAR(32)   NOT NULL COMMENT '孔跨布置',
    load_limit      DECIMAL(8,2)  NOT NULL COMMENT '限重（吨）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bridge_no (device_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tunnel (
    id              BIGINT        NOT NULL,
    device_no       VARCHAR(32)   NOT NULL,
    section_id      BIGINT        NOT NULL,
    mileage         DECIMAL(10,3) NOT NULL,
    install_date    DATE,
    last_check_date DATE,
    status          VARCHAR(16)   NOT NULL,
    remark          VARCHAR(255),
    tunnel_length   DECIMAL(8,2)  NOT NULL COMMENT '全长（米）',
    lining_type     VARCHAR(32)   NOT NULL COMMENT '衬砌类型',
    lighting        VARCHAR(16)   NOT NULL COMMENT 'NORMAL 正常 / FAULT 故障 / NONE 无',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tunnel_no (device_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 模块三：检查与病害 ----------------
CREATE TABLE inspection (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    inspect_no    VARCHAR(32) NOT NULL COMMENT '检查单号',
    section_id    BIGINT      NOT NULL,
    inspector     VARCHAR(32) NOT NULL COMMENT '检查人',
    inspect_date  DATE        NOT NULL,
    inspect_type  VARCHAR(16) NOT NULL COMMENT 'STATIC 静态 / DYNAMIC 动态 / RIDE 添乘',
    remark        VARCHAR(255),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspect_no (inspect_no),
    KEY idx_inspect_section (section_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE defect (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    inspection_id BIGINT        NOT NULL COMMENT '所属检查单',
    section_id    BIGINT        NOT NULL COMMENT '所属区段',
    defect_type   VARCHAR(16)   NOT NULL COMMENT 'CRACK/WEAR/SETTLEMENT/GEOMETRY/MUD/BOLT',
    severity      VARCHAR(16)   NOT NULL COMMENT 'LIGHT 轻 / MIDDLE 中 / HEAVY 重',
    mileage       DECIMAL(10,3) NOT NULL COMMENT '里程位置',
    description   VARCHAR(255)  NOT NULL,
    found_date    DATE          NOT NULL,
    closed        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否销号',
    close_date    DATE,
    treatment     VARCHAR(255)  COMMENT '整治措施',
    PRIMARY KEY (id),
    KEY idx_defect_section (section_id),
    KEY idx_defect_inspection (inspection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 模块四：养修作业 ----------------
CREATE TABLE work_order (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    order_no       VARCHAR(32) NOT NULL COMMENT '作业单号',
    section_id     BIGINT      NOT NULL,
    work_item      VARCHAR(24) NOT NULL COMMENT '起道/拨道/捣固/换轨/清筛/打磨/换枕/应力放散',
    window_date    DATE        NOT NULL COMMENT '天窗点日期',
    start_time     TIME        NOT NULL COMMENT '天窗起始时刻',
    end_time       TIME        NOT NULL COMMENT '天窗结束时刻',
    leader         VARCHAR(32) NOT NULL COMMENT '施工负责人',
    worker_count   INT         NOT NULL,
    cooperate_unit VARCHAR(64) COMMENT '配合单位',
    status         VARCHAR(16) NOT NULL COMMENT 'PLAN/APPROVED/WORKING/CHECKED/CLOSED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_section (section_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 模块四（扩展）：封锁解除交接台 ----------------
-- 已验收作业不能直接销号，必须由施工负责人发起本交接单、另一名复核人给出唯一结论；
-- version 乐观锁保证并发复核只有一份结论生效。
CREATE TABLE block_release (
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    release_no                  VARCHAR(32)  NOT NULL COMMENT '交接单号',
    work_order_id               BIGINT       NOT NULL COMMENT '对应的养修作业单',
    section_id                  BIGINT       NOT NULL COMMENT '对应区段',
    site_clear                  VARCHAR(255) NOT NULL COMMENT '现场清障结果',
    staff_withdrawn             VARCHAR(255) NOT NULL COMMENT '人员撤离结果',
    equipment_removed           VARCHAR(255) NOT NULL COMMENT '机具撤出结果',
    retest_result               VARCHAR(255) NOT NULL COMMENT '线路复测结果',
    suggestion                  VARCHAR(16)  NOT NULL COMMENT 'NORMAL 恢复正常/SPEED_LIMIT 继续限速/BLOCKED 继续封锁',
    suggested_speed             INT          COMMENT '建议限速值',
    limit_reason                VARCHAR(255) COMMENT '限速原因',
    leader                      VARCHAR(32)  NOT NULL COMMENT '施工负责人（发起人）',
    reviewer                    VARCHAR(32)  COMMENT '复核人（不得与负责人相同）',
    status                      VARCHAR(16)  NOT NULL COMMENT 'PENDING 待复核/APPROVED 已通过/REJECTED 已退回/INVALID 已失效',
    snapshot_work_status        VARCHAR(16)  NOT NULL COMMENT '发起时作业单状态快照',
    snapshot_section_status     VARCHAR(16)  NOT NULL COMMENT '发起时区段状态快照',
    snapshot_section_speed      INT          COMMENT '发起时区段限速值快照',
    snapshot_heavy_defect_ids   VARCHAR(1000) NOT NULL COMMENT '发起时未销号重级病害id清单，逗号分隔',
    snapshot_heavy_defect_text  VARCHAR(2000) NOT NULL COMMENT '发起时未销号重级病害摘要',
    final_decision              VARCHAR(16)  COMMENT '复核最终结论 NORMAL/SPEED_LIMIT/BLOCKED',
    final_speed                 INT          COMMENT '最终限速值',
    final_reason                VARCHAR(255) COMMENT '复核意见/限速原因',
    submitted_at                DATETIME     NOT NULL COMMENT '提交（含重提）时间',
    reviewed_at                 DATETIME     COMMENT '复核时间',
    invalidated_at              DATETIME     COMMENT '失效时间',
    invalid_reason              VARCHAR(500) COMMENT '失效变化项说明',
    version                     BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_no (release_no),
    KEY idx_release_work (work_order_id),
    KEY idx_release_section (section_id),
    KEY idx_release_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 交接处理轨迹：每次提交/重提/退回/通过/失效各一条，保留时间、人员、说明
CREATE TABLE block_release_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    release_id    BIGINT       NOT NULL,
    action        VARCHAR(16)  NOT NULL COMMENT 'SUBMIT/RESUBMIT/REJECT/APPROVE/INVALIDATE',
    operator      VARCHAR(32)  NOT NULL COMMENT '操作人（失效为系统）',
    acted_at      DATETIME     NOT NULL,
    remark        VARCHAR(1000) COMMENT '说明/意见/失效变化项',
    result_status VARCHAR(16)  NOT NULL COMMENT '动作后申请状态',
    PRIMARY KEY (id),
    KEY idx_release_log (release_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 模块五：物资与机具 ----------------
CREATE TABLE material (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    name         VARCHAR(64) NOT NULL,
    spec         VARCHAR(64) NOT NULL,
    unit         VARCHAR(16) NOT NULL,
    stock_qty    INT         NOT NULL,
    safety_stock INT         NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE equipment (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    equip_no           VARCHAR(32) NOT NULL,
    name               VARCHAR(64) NOT NULL,
    model              VARCHAR(32) NOT NULL,
    last_maintain_date DATE,
    next_maintain_date DATE,
    status             VARCHAR(16) NOT NULL COMMENT 'AVAILABLE/IN_USE/MAINTAIN/SCRAP',
    PRIMARY KEY (id),
    UNIQUE KEY uk_equip_no (equip_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;

-- =========================== 演示数据 ===========================

INSERT INTO line (id, name, start_station, end_station, mileage_km, direction, bureau) VALUES
(1,  '京广上行', '北京西', '广州南', 2298.000, 'UP',   '中国铁路北京局'),
(2,  '京广下行', '广州南', '北京西', 2298.000, 'DOWN', '中国铁路广州局'),
(3,  '沪昆下行', '上海虹桥', '昆明南', 2252.000, 'DOWN', '中国铁路上海局'),
(4,  '陇海上行', '连云港东', '兰州西', 1759.000, 'UP',   '中国铁路郑州局'),
(5,  '京哈上行', '北京站', '哈尔滨西', 1241.000, 'UP',   '中国铁路沈阳局'),
(6,  '广深线',   '广州东', '深圳站',   147.000, 'UP',   '中国铁路广州局'),
(7,  '成渝高铁', '成都东', '重庆北',   308.000, 'UP',   '中国铁路成都局'),
(8,  '大秦重载线', '大同', '秦皇岛',   653.000, 'UP',   '中国铁路太原局'),
(9,  '京沪上行', '北京南', '上海虹桥', 1318.000, 'UP',   '中国铁路济南局'),
(10, '胶济客专', '青岛',   '济南',     362.500, 'DOWN', '中国铁路济南局');

INSERT INTO section (id, line_id, section_no, start_mile, end_mile, rail_type, bed_type, design_speed, status, speed_limit, remark) VALUES
(1,  1, 'GX-01',  10.000,  16.500, '60KG', 'BALLAST', 160, 'NORMAL',      160, '北京西出站段'),
(2,  1, 'GX-02',  16.500,  24.800, '60KG', 'BALLAST', 160, 'SPEED_LIMIT',  45, '路基沉降整治，限速运行'),
(3,  1, 'GX-03',  24.800,  33.200, '75KG', 'SLAB',    200, 'NORMAL',      200, '无砟轨道试验段'),
(4,  1, 'GX-04',  33.200,  41.600, '60KG', 'BALLAST', 160, 'BLOCKED',       0, '换轨施工封锁中'),
(5,  1, 'GX-05',  41.600,  50.000, '60KG', 'SLAB',    200, 'NORMAL',      200, '含 2 座隧道'),
(6,  1, 'GX-06',  50.000,  58.400, '75KG', 'BALLAST', 200, 'SPEED_LIMIT',  80, '桥梁养护限速'),
(7,  2, 'GXD-01', 12.300,  18.500, '60KG', 'BALLAST', 160, 'NORMAL',      160, ''),
(8,  2, 'GXD-02', 18.500,  26.900, '60KG', 'SLAB',    160, 'NORMAL',      160, ''),
(9,  3, 'HKD-01',120.000, 128.400, '60KG', 'BALLAST', 200, 'NORMAL',      200, ''),
(10, 3, 'HKD-02',128.400, 136.800, '75KG', 'SLAB',    200, 'SPEED_LIMIT', 120, '隧道渗水整治限速'),
(11, 3, 'HKD-03',136.800, 145.200, '60KG', 'BALLAST', 200, 'NORMAL',      200, ''),
(12, 6, 'GS-01',   5.000,  12.600, '60KG', 'SLAB',    200, 'NORMAL',      200, ''),
(13, 6, 'GS-02',  12.600,  20.200, '60KG', 'BALLAST', 160, 'SPEED_LIMIT', 100, ''),
(14, 9, 'JH-01',  88.000,  95.500, '75KG', 'SLAB',    250, 'NORMAL',      250, '高速区段');

-- 序列：TABLE 策略从 1000 起发号，避开演示数据占用的 1~48
INSERT INTO device_id_gen (gen_key, gen_value) VALUES ('device', 1000);

-- 钢轨（10 行）
INSERT INTO rail (id, device_no, section_id, mileage, install_date, last_check_date, status, remark, rail_type, rail_length, lock_temp) VALUES
(1,  'RAIL-GX-01-01', 1, 11.200, '2019-05-12', '2026-08-15', 'GOOD',  '左股', '60KG', 100.00, 26.5),
(2,  'RAIL-GX-01-02', 1, 13.800, '2019-05-12', '2026-08-15', 'GOOD',  '右股', '60KG', 100.00, 26.5),
(3,  'RAIL-GX-02-01', 2, 18.300, '2018-09-20', '2026-07-28', 'WATCH', '左股磨耗偏大', '60KG', 100.00, 24.0),
(4,  'RAIL-GX-02-02', 2, 21.600, '2018-09-20', '2026-07-28', 'FAULT', '右股剥离掉块', '60KG',  75.00, 24.0),
(5,  'RAIL-GX-03-01', 3, 26.400, '2021-03-08', '2026-09-02', 'GOOD',  '无缝线路', '75KG', 500.00, 28.0),
(6,  'RAIL-GX-03-02', 3, 30.100, '2021-03-08', '2026-09-02', 'GOOD',  '无缝线路', '75KG', 500.00, 28.0),
(7,  'RAIL-GX-04-01', 4, 35.700, '2016-11-02', '2026-06-11', 'FAULT', '待换轨', '60KG', 100.00, 22.5),
(11, 'RAIL-GX-05-01', 5, 43.900, '2020-07-19', '2026-05-20', 'WATCH', '超 90 天未检', '60KG', 100.00, 25.0),
(12, 'RAIL-GX-06-01', 6, 52.300, '2020-07-19', '2026-09-10', 'GOOD',  '桥上无缝线路', '75KG', 500.00, 27.5),
(13, 'RAIL-GXD-01-01', 7, 14.500, '2018-04-26', '2026-08-02', 'GOOD', '左股', '60KG', 100.00, 25.5);

-- 道岔（10 行）
INSERT INTO `switch` (id, device_no, section_id, mileage, install_date, last_check_date, status, remark, switch_model, direction, machine_model) VALUES
(14, 'SW-GX-01-01', 1, 12.600, '2017-06-15', '2026-09-05', 'GOOD',  '站内 3 号岔', '12号', 'LEFT',  'S700K'),
(15, 'SW-GX-01-02', 1, 15.100, '2017-06-15', '2026-09-05', 'GOOD',  '站内 5 号岔', '12号', 'RIGHT', 'S700K'),
(16, 'SW-GX-02-01', 2, 19.400, '2015-08-22', '2026-07-16', 'WATCH', '尖轨磨耗', '9号',  'LEFT',  'ZD6-D'),
(17, 'SW-GX-02-02', 2, 23.200, '2015-08-22', '2026-07-16', 'FAULT', '转辙机动作不良', '9号', 'RIGHT', 'ZD6-D'),
(18, 'SW-GX-03-01', 3, 27.800, '2021-03-08', '2026-09-02', 'GOOD',  '高速可动心轨', '18号', 'LEFT', 'S700K'),
(19, 'SW-GX-03-02', 3, 31.500, '2021-03-08', '2026-09-02', 'GOOD',  '高速可动心轨', '18号', 'RIGHT', 'S700K'),
(20, 'SW-GX-04-01', 4, 37.200, '2016-11-02', '2026-06-11', 'WATCH', '封锁区岔区', '12号', 'LEFT', 'ZD6-E'),
(21, 'SW-GX-05-01', 5, 45.600, '2020-07-19', '2026-08-20', 'GOOD',  '', '12号', 'RIGHT', 'S700K'),
(22, 'SW-GX-06-01', 6, 54.100, '2020-07-19', '2026-09-10', 'GOOD',  '', '12号', 'LEFT',  'S700K'),
(23, 'SW-GXD-01-01', 7, 16.800, '2018-04-26', '2026-08-02', 'FAULT', '表示杆旷动', '9号', 'RIGHT', 'ZD6-D');

-- 轨枕（10 行）
INSERT INTO sleeper (id, device_no, section_id, mileage, install_date, last_check_date, status, remark, sleeper_type, sleeper_count) VALUES
(24, 'SLP-GX-01-01', 1, 11.200, '2019-05-12', '2026-08-15', 'GOOD',  '每公里铺设', 'CONCRETE', 1760),
(25, 'SLP-GX-01-02', 1, 14.000, '2019-05-12', '2026-08-15', 'GOOD',  '每公里铺设', 'CONCRETE', 1760),
(26, 'SLP-GX-02-01', 2, 18.300, '2018-09-20', '2026-07-28', 'WATCH', '部分失效', 'CONCRETE', 1680),
(27, 'SLP-GX-02-02', 2, 22.400, '2013-06-11', '2026-07-28', 'FAULT', '木枕腐朽待换', 'WOOD', 1440),
(28, 'SLP-GX-03-01', 3, 26.400, '2021-03-08', '2026-09-02', 'GOOD',  '无砟配套', 'CONCRETE', 1840),
(29, 'SLP-GX-03-02', 3, 30.900, '2021-03-08', '2026-09-02', 'GOOD',  '无砟配套', 'CONCRETE', 1840),
(30, 'SLP-GX-04-01', 4, 35.700, '2016-11-02', '2026-06-11', 'WATCH', '随换轨同步更换', 'CONCRETE', 1760),
(31, 'SLP-GX-05-01', 5, 43.900, '2020-07-19', '2026-05-20', 'WATCH', '超 90 天未检', 'CONCRETE', 1760),
(32, 'SLP-GX-06-01', 6, 52.300, '2020-07-19', '2026-09-10', 'GOOD',  '桥枕', 'CONCRETE', 1800),
(33, 'SLP-GXD-01-01', 7, 14.500, '2018-04-26', '2026-08-02', 'GOOD',  '每公里铺设', 'CONCRETE', 1760);

-- 桥梁（10 行）
INSERT INTO bridge (id, device_no, section_id, mileage, install_date, last_check_date, status, remark, bridge_length, span_layout, load_limit) VALUES
(34, 'BRG-GX-02-01', 2, 20.100, '2010-09-30', '2026-07-28', 'WATCH', '梁体裂纹观测中', 132.40, '4-32m', 60.00),
(35, 'BRG-GX-03-01', 3, 28.600, '2015-05-18', '2026-09-02', 'GOOD',  '', 265.80, '8-32m', 80.00),
(36, 'BRG-GX-03-02', 3, 32.400, '2015-05-18', '2026-09-02', 'GOOD',  '', 96.50,  '3-32m', 80.00),
(37, 'BRG-GX-04-01', 4, 38.900, '2008-12-06', '2026-06-11', 'FAULT', '墩台沉降超限', 210.30, '6-32m', 45.00),
(38, 'BRG-GX-05-01', 5, 47.200, '2018-10-22', '2026-05-20', 'WATCH', '超 90 天未检', 158.60, '5-32m', 60.00),
(39, 'BRG-GX-06-01', 6, 52.300, '2012-07-14', '2026-09-10', 'GOOD',  '桥上无缝线路', 342.70, '10-32m', 80.00),
(40, 'BRG-GX-06-02', 6, 56.800, '2012-07-14', '2026-09-10', 'GOOD',  '', 128.00, '4-32m', 80.00),
(41, 'BRG-HKD-01-01', 9, 123.400, '2014-04-09', '2026-08-18', 'GOOD', '', 186.20, '6-32m', 70.00),
(42, 'BRG-HKD-02-01', 10, 131.700, '2014-04-09', '2026-08-18', 'WATCH', '支座老化', 152.40, '5-32m', 70.00),
(43, 'BRG-GS-01-01', 12, 8.600, '2019-02-25', '2026-09-01', 'GOOD', '', 98.30, '3-32m', 60.00);

-- 隧道（8 行）
INSERT INTO tunnel (id, device_no, section_id, mileage, install_date, last_check_date, status, remark, tunnel_length, lining_type, lighting) VALUES
(44, 'TUN-GX-05-01', 5, 44.800, '2016-08-08', '2026-05-20', 'WATCH', '衬砌渗水', 1280.00, '复合式衬砌', 'NORMAL'),
(45, 'TUN-GX-05-02', 5, 48.600, '2016-08-08', '2026-05-20', 'FAULT', '照明故障待修', 860.00,  '整体式衬砌', 'FAULT'),
(46, 'TUN-GX-06-01', 6, 55.200, '2012-07-14', '2026-09-10', 'GOOD',  '', 2140.00, '复合式衬砌', 'NORMAL'),
(47, 'TUN-HKD-02-01', 10, 133.500, '2013-11-19', '2026-08-18', 'WATCH', '渗水整治中', 1680.00, '复合式衬砌', 'NORMAL'),
(48, 'TUN-HKD-02-02', 10, 135.900, '2013-11-19', '2026-08-18', 'GOOD',  '', 540.00,  '喷射混凝土衬砌', 'NONE'),
(49, 'TUN-HKD-03-01', 11, 140.300, '2013-11-19', '2026-08-18', 'GOOD',  '', 960.00,  '复合式衬砌', 'NORMAL'),
(50, 'TUN-GS-02-01', 13, 16.400, '2019-02-25', '2026-09-01', 'WATCH', '洞口边仰坡', 720.00,  '整体式衬砌', 'NORMAL'),
(51, 'TUN-JH-01-01', 14, 91.200, '2011-06-30', '2026-08-25', 'GOOD',  '', 1560.00, '复合式衬砌', 'NORMAL');

INSERT INTO inspection (id, inspect_no, section_id, inspector, inspect_date, inspect_type, remark) VALUES
(1,  'INS-2026-0001', 1, '王建国', '2026-09-18', 'STATIC',  '月度静态检查'),
(2,  'INS-2026-0002', 2, '李长顺', '2026-09-15', 'STATIC',  '沉降段加密检查'),
(3,  'INS-2026-0003', 2, '赵铁军', '2026-09-16', 'DYNAMIC', '轨检车动态检测'),
(4,  'INS-2026-0004', 3, '孙明远', '2026-09-10', 'DYNAMIC', '高速段综合检测'),
(5,  'INS-2026-0005', 4, '周保平', '2026-09-08', 'STATIC',  '封锁施工前检查'),
(6,  'INS-2026-0006', 5, '吴国庆', '2026-09-05', 'STATIC',  '隧道段检查'),
(7,  'INS-2026-0007', 6, '郑高原', '2026-09-12', 'RIDE',    '添乘检查'),
(8,  'INS-2026-0008', 7, '冯志强', '2026-09-14', 'STATIC',  '下行线月度检查'),
(9,  'INS-2026-0009', 10, '陈沪生', '2026-09-09', 'STATIC',  '隧道渗水专项'),
(10, 'INS-2026-0010', 14, '刘京沪', '2026-09-20', 'DYNAMIC', '高速综合检测车');

INSERT INTO defect (id, inspection_id, section_id, defect_type, severity, mileage, description, found_date, closed, close_date, treatment) VALUES
(1,  1, 1, 'WEAR',       'LIGHT',  11.900, '左股轨面轻微磨耗，磨耗量 3mm', '2026-09-18', 0, NULL, NULL),
(2,  2, 2, 'SETTLEMENT', 'HEAVY',  19.200, '路基沉降导致高低超限 22mm，需限速', '2026-09-02', 0, NULL, NULL),
(3,  2, 2, 'MUD',        'MIDDLE', 20.400, '道床翻浆冒泥 8m，枕端吊空', '2026-09-15', 0, NULL, NULL),
(4,  2, 2, 'BOLT',       'LIGHT',  21.100, '接头螺栓松动 4 套', '2026-09-15', 1, '2026-09-17', '复紧并补齐弹条扣件'),
(5,  3, 2, 'GEOMETRY',   'HEAVY',  22.600, '轨距+11mm 三角坑超限', '2026-09-05', 0, NULL, NULL),
(6,  3, 2, 'CRACK',      'MIDDLE', 23.400, '右股轨头核伤裂纹 12mm', '2026-09-16', 0, NULL, NULL),
(7,  4, 3, 'GEOMETRY',   'LIGHT',  28.900, '高低 6mm，方向 5mm', '2026-09-10', 0, NULL, NULL),
(8,  4, 3, 'WEAR',       'LIGHT',  30.600, '道岔区轨侧磨耗 4mm', '2026-09-10', 0, NULL, NULL),
(9,  5, 4, 'CRACK',      'HEAVY',  36.400, '钢轨轨腰纵向裂纹 120mm，立即更换', '2026-09-01', 1, '2026-09-03', '锯轨换轨并复焊'),
(10, 6, 5, 'SETTLEMENT', 'MIDDLE', 44.800, '隧道内整体道床沉降 9mm', '2026-09-05', 0, NULL, NULL),
(11, 6, 5, 'MUD',        'LIGHT',  47.500, '隧道渗水引起轻微翻浆', '2026-09-05', 0, NULL, NULL),
(12, 7, 6, 'BOLT',       'LIGHT',  52.800, '桥上扣件扭矩不足', '2026-09-12', 0, NULL, NULL),
(13, 9, 10, 'SETTLEMENT','HEAVY', 133.800, '隧道基底沉降 18mm，限速 120', '2026-09-09', 0, NULL, NULL),
(14, 10, 14, 'GEOMETRY',  'LIGHT',  91.800, '高速段轨向 4mm，纳入计划修', '2026-09-20', 0, NULL, NULL);

INSERT INTO work_order (id, order_no, section_id, work_item, window_date, start_time, end_time, leader, worker_count, cooperate_unit, status) VALUES
(1,  'WO-2026-0001', 1, 'TAMP',           '2026-09-26', '00:30:00', '04:00:00', '王建国', 32, '北京电务段', 'APPROVED'),
(2,  'WO-2026-0002', 2, 'LIFT',           '2026-09-27', '00:20:00', '03:50:00', '李长顺', 45, '北京供电段', 'APPROVED'),
(3,  'WO-2026-0003', 2, 'SCREEN',         '2026-09-28', '01:00:00', '05:00:00', '赵铁军', 60, '北京工务机械段', 'PLAN'),
(4,  'WO-2026-0004', 3, 'GRIND',          '2026-09-29', '00:10:00', '03:40:00', '孙明远', 28, NULL, 'PLAN'),
(5,  'WO-2026-0005', 4, 'RAIL_CHANGE',    '2026-09-30', '00:00:00', '04:30:00', '周保平', 52, '北京电务段', 'APPROVED'),
(6,  'WO-2026-0006', 1, 'ALIGN',          '2026-09-12', '00:30:00', '03:30:00', '王建国', 24, NULL, 'CLOSED'),
(7,  'WO-2026-0007', 2, 'STRESS',         '2026-09-14', '01:20:00', '05:20:00', '李长顺', 38, NULL, 'CHECKED'),
(8,  'WO-2026-0008', 5, 'SLEEPER_CHANGE', '2026-09-21', '00:40:00', '04:10:00', '吴国庆', 40, '北京工务机械段', 'WORKING'),
(9,  'WO-2026-0009', 6, 'TAMP',           '2026-09-22', '00:30:00', '03:30:00', '郑高原', 26, NULL, 'CHECKED'),
(10, 'WO-2026-0010', 7, 'GRIND',          '2026-10-02', '00:20:00', '03:20:00', '冯志强', 22, '广州电务段', 'PLAN'),
(11, 'WO-2026-0011', 10, 'SCREEN',        '2026-10-04', '01:00:00', '04:30:00', '陈沪生', 48, '上海工务段', 'PLAN'),
(12, 'WO-2026-0012', 14, 'TAMP',          '2026-10-06', '00:30:00', '03:00:00', '刘京沪', 30, '济南工务段', 'PLAN'),
(13, 'WO-2026-0013', 6, 'ALIGN',          '2026-09-19', '00:30:00', '03:00:00', '郑高原', 20, NULL, 'CLOSED'),
(14, 'WO-2026-0014', 2, 'LIFT',           '2026-09-23', '00:15:00', '04:45:00', '赵铁军', 42, '北京工务机械段', 'CHECKED');

-- 封锁解除交接演示数据：待复核 / 已退回 / 已失效 / 已通过 四种状态各一
INSERT INTO block_release (id, release_no, work_order_id, section_id,
    site_clear, staff_withdrawn, equipment_removed, retest_result,
    suggestion, suggested_speed, limit_reason, leader, reviewer, status,
    snapshot_work_status, snapshot_section_status, snapshot_section_speed,
    snapshot_heavy_defect_ids, snapshot_heavy_defect_text,
    final_decision, final_speed, final_reason, submitted_at, reviewed_at, invalidated_at, invalid_reason, version)
VALUES
(1, 'BR-2026-0001', 9, 6,
 '限界内路料、旧枕全部清运完毕，栅栏恢复', '作业人员 26 人已全部撤至安全区，点名确认', '捣固机、起拨道机 3 台已撤出并回库登记', '轨道检查仪复测高低 3mm、轨向 2mm，符合验收标准',
 'SPEED_LIMIT', 80, '桥梁养护常规限速 80km/h，待观察一周后恢复', '郑高原', NULL, 'PENDING',
 'CHECKED', 'SPEED_LIMIT', 80,
 '', '无未销号重级病害',
 NULL, NULL, NULL, '2026-09-22 03:35:00', NULL, NULL, NULL, 0),
(2, 'BR-2026-0002', 7, 2,
 '道床清筛弃砟已清运，排水畅通', '应力放散人员 38 人全部撤离', '拉伸器、撞轨器全部撤出', '锁定轨温复测 24.5℃，位移观测桩无异常',
 'SPEED_LIMIT', 45, '路基沉降与几何超限两处重级病害未销号，只能继续限速', '李长顺', '赵铁军', 'REJECTED',
 'CHECKED', 'SPEED_LIMIT', 45,
 '2,5', 'K19+200 路基沉降导致高低超限 22mm，需限速；K22+600 轨距+11mm 三角坑超限',
 NULL, NULL, NULL, '2026-09-23 02:10:00', '2026-09-23 09:20:00', NULL, NULL, 1),
(3, 'BR-2026-0003', 14, 2,
 '清筛弃砟已清运', '人员已撤离', '机具已撤出', '复测几何尺寸达标',
 'SPEED_LIMIT', 45, '两处重级病害未销号，继续限速观测', '赵铁军', '李长顺', 'INVALID',
 'CHECKED', 'SPEED_LIMIT', 60,
 '2', 'K19+200 路基沉降导致高低超限 22mm，需限速',
 NULL, NULL, NULL, '2026-09-23 05:30:00', NULL, '2026-09-24 12:00:00',
 '未销号重级病害：K19+200 路基沉降导致高低超限 22mm，需限速 → 无未销号重级病害（已销号/消失：K19+200 路基沉降导致高低超限 22mm，需限速；新发现/升级：K22+600 轨距+11mm 三角坑超限）', 2),
(4, 'BR-2026-0004', 13, 6,
 '拨道后线路两侧无侵限物，标志标桩齐全', '作业人员 20 人全部撤离并点名', '起拨道机、轨道检查仪全部撤出', '复测方向 2mm、水平差 1mm，满足常速放行条件',
 'NORMAL', NULL, NULL, '郑高原', '王建国', 'APPROVED',
 'CHECKED', 'SPEED_LIMIT', 80,
 '', '无未销号重级病害',
 'NORMAL', NULL, '四项现场确认齐全，无未销号重级病害，同意恢复正常', '2026-09-19 03:15:00', '2026-09-19 08:40:00', NULL, NULL, 1);

INSERT INTO block_release_log (id, release_id, action, operator, acted_at, remark, result_status) VALUES
(1, 1, 'SUBMIT', '郑高原', '2026-09-22 03:35:00', '发起封锁解除申请，建议：继续限速 80km/h', 'PENDING'),
(2, 2, 'SUBMIT', '李长顺', '2026-09-22 05:05:00', '发起封锁解除申请，建议：继续限速 60km/h', 'PENDING'),
(3, 2, 'REJECT', '赵铁军', '2026-09-22 10:00:00', '复测记录缺少轨检车签字，且限速原因未注明病害编号，退回补充整改', 'REJECTED'),
(4, 2, 'RESUBMIT', '李长顺', '2026-09-23 02:10:00', '按最新现场重新提交，建议：继续限速 45km/h', 'PENDING'),
(5, 3, 'SUBMIT', '赵铁军', '2026-09-23 05:30:00', '发起封锁解除申请，建议：继续限速 45km/h', 'PENDING'),
(6, 3, 'INVALIDATE', '系统', '2026-09-24 12:00:00',
 '复核失效：区段重级病害销号情况变化（病害 2 已销号、新增病害 5 未销号）', 'INVALID'),
(7, 4, 'SUBMIT', '郑高原', '2026-09-19 03:15:00', '发起封锁解除申请，建议：恢复正常', 'PENDING'),
(8, 4, 'APPROVE', '王建国', '2026-09-19 08:40:00', '复核通过，结论：恢复正常；意见：四项现场确认齐全，无未销号重级病害', 'APPROVED');

INSERT INTO material (id, name, spec, unit, stock_qty, safety_stock) VALUES
(1,  '60kg/m 钢轨',    'U71Mn 100m 定尺', '根', 86,  40),
(2,  '75kg/m 钢轨',    'U75V 500m 长轨',  '根', 24,  20),
(3,  '混凝土轨枕',     'Ⅲ型 砼枕',         '根', 1520, 800),
(4,  '弹条扣件',       'Ⅱ型弹条',          '套', 640, 1200),
(5,  '一级道砟',       '20-70mm 花岗岩',   '吨', 320, 500),
(6,  '接头夹板',       '60kg/m 双头',      '块', 58,  60),
(7,  '绝缘接头',       '60kg/m 胶接',      '套', 12,  15),
(8,  '道岔尖轨',       '12号 左开',        '根', 6,   8),
(9,  '轨距挡板',       '60kg/m 尼龙',      '件', 2100, 1500),
(10, '液压油',         '46# 抗磨',         '桶', 9,   20);

INSERT INTO equipment (id, equip_no, name, model, last_maintain_date, next_maintain_date, status) VALUES
(1, 'EQ-0001', '捣固车',     'DC-32Ⅱ',  '2026-04-10', '2026-10-07', 'IN_USE'),
(2, 'EQ-0002', '清筛机',     'QS-650',   '2026-03-18', '2026-09-14', 'MAINTAIN'),
(3, 'EQ-0003', '钢轨打磨车', 'PGM-48',   '2026-05-22', '2026-11-18', 'AVAILABLE'),
(4, 'EQ-0004', '液压起拨道机','YQB-200',  '2026-06-05', '2026-12-02', 'AVAILABLE'),
(5, 'EQ-0005', '钢轨探伤仪', 'GCT-8C',   '2026-07-01', '2026-10-05', 'IN_USE'),
(6, 'EQ-0006', '轨道检查仪', 'GJY-T-4',  '2026-06-28', '2026-12-25', 'AVAILABLE'),
(7, 'EQ-0007', '应力放散车', 'YSF-300',  '2026-02-14', '2026-08-13', 'MAINTAIN'),
(8, 'EQ-0008', '换轨车',     'HG-120',   '2026-05-09', '2026-11-05', 'AVAILABLE'),
(9, 'EQ-0009', '旧枕抽换机', 'CX-90',    '2025-11-20', '2026-05-19', 'SCRAP'),
(10, 'EQ-0010', '照明车',    'ZM-400',   '2026-08-02', '2027-01-29', 'AVAILABLE');
