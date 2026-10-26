CREATE TABLE `SCSJ_FACT_INPUT_VERSION` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '输入版本主键',
    `account_id` VARCHAR(64) NOT NULL COMMENT '广告账户ID；唯一身份锚点，不使用账户名称兜底',
    `stat_date` DATE NOT NULL COMMENT '统计日；Asia/Shanghai自然日',
    `project_ref` VARCHAR(128) NOT NULL COMMENT '采集项目引用；仅作追溯，不替代账户ID',
    `source_type` VARCHAR(32) NOT NULL COMMENT '来源枚举：MCP_AUTO=自动MCP采集；MANUAL_HISTORY=人工历史原件',
    `data_version` VARCHAR(255) NOT NULL COMMENT '来源数据版本字符串',
    `content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化rows内容的SHA-256；账户日版本隔离键',
    `schema_version` VARCHAR(128) NOT NULL COMMENT '来源material_day结构版本',
    `catalog_version` VARCHAR(128) NOT NULL COMMENT '采集字段目录版本',
    `manifest_path` TEXT NOT NULL COMMENT '主来源证据规范化绝对路径；MCP为manifest，人工历史为原件证据副本',
    `manifest_size_bytes` BIGINT UNSIGNED NOT NULL COMMENT '主来源证据字节数',
    `manifest_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '主来源证据文件字节SHA-256',
    `material_path` TEXT NOT NULL COMMENT '事实载荷规范化绝对路径；MCP为material_day，人工历史为每日facts.jsonl',
    `material_size_bytes` BIGINT UNSIGNED NOT NULL COMMENT '事实载荷文件字节数',
    `material_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事实载荷文件字节SHA-256',
    `disposition` VARCHAR(16) NOT NULL COMMENT '版本处置枚举：CURRENT=当前事实；PREVIOUS=上一回滚版；ISOLATED=第三种及以后不同内容，待人工处理',
    `row_count` INT UNSIGNED NOT NULL COMMENT '该输入版本的素材事实行数',
    `run_id` VARCHAR(128) NOT NULL COMMENT '校验与导入回执关联ID',
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间；数据库时钟',
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '记录最后修改时间；数据库时钟',
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_SCSJ_INPUT_ACCOUNT_DAY_HASH` (`account_id`, `stat_date`, `content_hash`),
    KEY `IX_SCSJ_INPUT_ACCOUNT_DAY` (`account_id`, `stat_date`),
    CONSTRAINT `CK_SCSJ_INPUT_SOURCE_TYPE` CHECK (`source_type` IN ('MCP_AUTO', 'MANUAL_HISTORY')),
    CONSTRAINT `CK_SCSJ_INPUT_DISPOSITION` CHECK (`disposition` IN ('CURRENT', 'PREVIOUS', 'ISOLATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户日事实输入版本及原件哈希证据';

CREATE TABLE `SCSJ_MATERIAL_DAY_FACT` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '素材日事实主键',
    `input_version_id` BIGINT UNSIGNED NOT NULL COMMENT '所属输入版本ID；关联SCSJ_FACT_INPUT_VERSION.id',
    `account_id` VARCHAR(64) NOT NULL COMMENT '广告账户ID；与输入版本一致',
    `stat_date` DATE NOT NULL COMMENT '统计日；与输入版本一致',
    `material_id` VARCHAR(128) NOT NULL COMMENT '来源素材ID；账户日输入版本内唯一',
    `source_row_number` INT UNSIGNED NOT NULL COMMENT 'material_day.rows中的一基序号',
    `raw_fact_json` LONGTEXT NOT NULL COMMENT '素材日来源行JSON；保留null、UNKNOWN、零及原值，不进行业务公式换算',
    `row_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'raw_fact_json UTF-8字节SHA-256；用于事务内逐行回读',
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间；数据库时钟',
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '记录最后修改时间；数据库时钟',
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_SCSJ_FACT_VERSION_MATERIAL` (`input_version_id`, `material_id`),
    KEY `IX_SCSJ_FACT_ACCOUNT_DAY` (`account_id`, `stat_date`),
    CONSTRAINT `CK_SCSJ_FACT_RAW_JSON` CHECK (JSON_VALID(`raw_fact_json`)),
    CONSTRAINT `FK_SCSJ_FACT_INPUT_VERSION` FOREIGN KEY (`input_version_id`)
        REFERENCES `SCSJ_FACT_INPUT_VERSION` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按输入版本隔离的素材日原始事实';

CREATE TABLE `SCSJ_FACT_DAY_STATE` (
    `account_id` VARCHAR(64) NOT NULL COMMENT '广告账户ID；账户日状态主键之一',
    `stat_date` DATE NOT NULL COMMENT '统计日；账户日状态主键之一',
    `current_input_version_id` BIGINT UNSIGNED NULL COMMENT '当前生效输入版本ID；空表示尚无已激活版本',
    `previous_input_version_id` BIGINT UNSIGNED NULL COMMENT '上一不同内容回滚版本ID；空表示尚无上一版',
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间；数据库时钟',
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '记录最后修改时间；数据库时钟',
    PRIMARY KEY (`account_id`, `stat_date`),
    UNIQUE KEY `UK_SCSJ_DAY_CURRENT_VERSION` (`current_input_version_id`),
    UNIQUE KEY `UK_SCSJ_DAY_PREVIOUS_VERSION` (`previous_input_version_id`),
    CONSTRAINT `FK_SCSJ_DAY_CURRENT_VERSION` FOREIGN KEY (`current_input_version_id`)
        REFERENCES `SCSJ_FACT_INPUT_VERSION` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `FK_SCSJ_DAY_PREVIOUS_VERSION` FOREIGN KEY (`previous_input_version_id`)
        REFERENCES `SCSJ_FACT_INPUT_VERSION` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `CK_SCSJ_DAY_DISTINCT_VERSIONS` CHECK (
        `current_input_version_id` IS NULL
        OR `previous_input_version_id` IS NULL
        OR `current_input_version_id` <> `previous_input_version_id`
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户日当前与上一输入版本指针及并发锁定行';
